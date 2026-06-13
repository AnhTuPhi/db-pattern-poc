# 29 - DB Hybrid Relational + Document (Postgres replaces MongoDB)

> "Should we use Mongo or Postgres?" is a false choice. The right
> question is "which fields are columns and which are JSONB?". When
> you answer that on a per-field basis, you build something that
> queries like Postgres and bends like Mongo — and you only run one
> database in production.

## Thesis

Modules 28 and 29 are a pair:

- **m28** — JSONB as a tool. The mechanics: GIN indexes, containment,
  the schemaless anti-pattern.
- **m29** — JSONB as a building block. The pattern: structured spine
  + document leaves on the same row.

The hybrid shape:

```
   ┌──────────────────────────── customer ────────────────────────────┐
   │  id            bigserial   PRIMARY KEY                            │
   │  tenant_id     bigint     NOT NULL  ← FK target, B-tree indexed  │
   │  email         text       NOT NULL  ← unique (tenant_id, email)  │
   │  created_at    timestamptz                                        │
   │  profile       jsonb      DEFAULT '{}'                            │
   │                  └─ marketingConsent, preferredChannel, tags[],   │
   │                     thirdParty.stripe/intercom/…                  │
   │                  └─ indexed: GIN(profile jsonb_path_ops)          │
   └───────────────────────────────────────────────────────────────────┘
                                   │ FK
                                   ▼
   ┌──────────────────────── customer_order ──────────────────────────┐
   │  id            bigserial   PRIMARY KEY                            │
   │  customer_id   bigint     NOT NULL  REFERENCES customer(id)       │
   │  status        text       NOT NULL  ← B-tree indexed              │
   │  total         numeric(18,2)         ← financial reports          │
   │  placed_at     timestamptz                                        │
   │  items         jsonb       NOT NULL  ← array of heterogeneous     │
   │                                        line items per product type│
   │                  └─ indexed: GIN(items jsonb_path_ops)            │
   │                  └─ CHECK (jsonb_typeof(items) = 'array')         │
   └───────────────────────────────────────────────────────────────────┘
```

The principle:

- **Columns** for fields every query touches, fields you index, fields
  that need a FK, fields you aggregate over.
- **JSONB** for fields that vary per row, per tenant, per product type,
  or per integration; fields that are sparse; fields whose shape will
  evolve faster than your DDL story can keep up.

## Setup

```bash
cd ..
docker compose --profile postgres up -d
./mvnw -pl 29-db-hybrid-relational-document -am spring-boot:run
```

App boots on **:8229**. Schema `m29_hybrid`. Two tables, both hybrid.

## Run order

```bash
# 1. Seed 3 tenants × 200 customers × 5 orders each.
curl -X POST 'localhost:8229/hybrid/seed?tenants=3&customers=200&orders=5' | jq

# 2. Spine query: column-indexed lookup. Index Scan on (tenant_id, email).
curl 'localhost:8229/hybrid/customer?tenantId=1&email=u4@tenant1.test' | jq

# 3. Leaf query #1: find customers with a tag in their profile.
curl 'localhost:8229/hybrid/customers/with-tag/vip' | jq

# 4. Leaf query #2: find orders containing a SKU anywhere in items[].
curl 'localhost:8229/hybrid/orders/with-sku/B-1042' | jq

# 5. Reporting: top SKUs by revenue across all orders.
#    This is the magic — SUM(qty * price) over an unnested JSONB array.
curl 'localhost:8229/hybrid/reports/top-skus?limit=10' | jq

# 6. Reporting that crosses the FK: revenue grouped by tenant.
curl 'localhost:8229/hybrid/reports/revenue-by-tenant' | jq

# 7. Patch a JSONB key without rewriting the whole profile.
curl -X POST 'localhost:8229/hybrid/customer/1/profile?key=preferredChannel&value=%22sms%22' | jq

# 8. Diagnostics — counts, table size, index sizes.
curl 'localhost:8229/hybrid/topology' | jq

# 9. The anti-pattern: what "single document per customer" would cost you.
curl -X POST 'localhost:8229/hybrid/anti-pattern' | jq
```

## What each piece proves

### Spine queries — `findCustomer`

```sql
SELECT id, tenant_id, email, profile
FROM customer WHERE tenant_id = ? AND email = ?;
```

The `unique (tenant_id, email)` index is a B-tree. Login lookups,
session resolution, every authenticated request — they all go through
this index. Putting these fields in JSONB would cost you stats, type
safety, and that O(log n) lookup. They belong in columns.

### Leaf queries — `findCustomersWithTag`, `findOrdersContainingSku`

```sql
-- "customers tagged vip"
SELECT id FROM customer
WHERE profile @> '{"tags":["vip"]}';

-- "orders that include sku X-1042"
SELECT id FROM customer_order
WHERE items @> '[{"sku":"X-1042"}]';
```

`@>` is the JSONB containment operator. It handles both objects and
arrays-of-objects out of the box. The GIN index (`jsonb_path_ops`)
makes both queries a Bitmap Index Scan. **This is exactly the
`db.orders.find({'items.sku':'X-1042'})` shape** that draws people to
MongoDB — and it works at MongoDB speeds in Postgres, on the same row
that also has a FK to its customer.

### Reporting — `lateral jsonb_array_elements`

The query that's *easy* in SQL and *hard* in a document store:

```sql
SELECT item->>'sku' AS sku,
       SUM((item->>'qty')::int) AS units,
       SUM((item->>'qty')::int * (item->>'price')::numeric) AS revenue
FROM customer_order o,
     LATERAL jsonb_array_elements(o.items) AS item
WHERE o.status = 'PLACED'
GROUP BY 1
ORDER BY 3 DESC
LIMIT 10;
```

`jsonb_array_elements` explodes the items array into one row per
element. The lateral join then carries the parent order's columns
forward, and you can `GROUP BY` across the whole dataset. Same idea as
MongoDB's `$unwind` + `$group`, but with the full SQL standard
available — `JOIN`, window functions, CTEs, `HAVING`.

For revenue-by-tenant, the lateral expansion joins back to `customer`
to recover the tenant id (which lives on the customer, not the order
— a classic relational normalization). **The FK is what makes this
query possible**; in a pure-document store you'd have denormalized
`tenant_id` onto every order to answer this.

### Mutations — `patchProfile`

```sql
UPDATE customer
SET profile = profile || jsonb_build_object('preferredChannel', '"sms"'::jsonb)
WHERE id = ?;
```

The `||` operator merges keys into a JSONB object — adding a new key
or overwriting an existing one. Equivalent to MongoDB's
`{$set: {preferredChannel: 'sms'}}` and just as cheap. No
read-modify-write round trip, atomic at the row level.

### Anti-pattern — single-document-per-customer

`/hybrid/anti-pattern` walks through three queries and what each would
cost in the embedded shape (where `customer.doc.orders[]` is the only
table). Summary:

| Query                  | Hybrid (this module)             | Embedded-document shape                          |
|------------------------|----------------------------------|--------------------------------------------------|
| Find order by id       | PK index, ~20µs                  | Scan every customer doc, hope to GIN-index path  |
| Update one order       | One row write                    | Rewrite the WHOLE customer doc                   |
| Revenue by tenant      | FK join + lateral unnest         | Unnest every doc's orders[]; no separate index   |
| Atomic write to 2 orders for same user | One tx | One tx + write amplification     |
| Atomic write to 2 orders for diff users | One tx | Two doc updates, no cross-doc tx in Mongo |

The general lesson: **embedding is the right call when the children
have no identity outside the parent and are read together** (line
items inside an order — almost always co-accessed, almost never
queried alone). It is the wrong call when the children have identity
and are queried independently (orders — you absolutely will need
"all orders this month across all customers").

## When the hybrid pattern is right

- You'd be tempted to use both Postgres AND Mongo. Now you can use just one.
- Most of your domain has a known relational shape, but **some** fields
  are sparse / polymorphic / vendor-specific.
- You need real transactions across the document AND its parent rows.
- You want SQL reports — joins, group bys, window functions — over
  the document-ish data.
- You have a strict-shape spine (auth, billing, regulatory data) AND
  a wild-west extension layer (per-tenant custom fields, third-party
  integration blobs).

## When to NOT do the hybrid pattern

- **Pure document workload, no joins, no aggregates.** A document store
  optimized for that workload (DynamoDB, Mongo, Couchbase) will be
  faster per-call and easier to operate. Postgres is a fine document
  store; it isn't the *fastest* one.
- **No FK relationships, ever.** If "customer" and "order" never join,
  and you never group across them, the spine is doing nothing for
  you.
- **Hot-path reads on JSONB fields with strict types.** If you find
  yourself adding `numeric(18,2)`-grade precision constraints on
  JSONB fields, the field wants to be a column.

## Production checklist

| Symptom                                          | Likely cause                                      | Fix                                                          |
|--------------------------------------------------|---------------------------------------------------|--------------------------------------------------------------|
| JSONB containment query is a Seq Scan            | No GIN index, or expression doesn't match operator | `CREATE INDEX ... USING gin (col jsonb_path_ops)`.            |
| `data->>'field'` slow                            | GIN doesn't help text extraction                  | Functional index on `(col->>'field')`.                       |
| Reporting query takes 30s                        | Unnesting huge arrays without statistics          | `ANALYZE`; consider extracting the field into a column.      |
| Updates have write amplification                 | You're rewriting whole docs instead of merging    | Use `||` / `jsonb_set` / `jsonb_insert` to patch in place.   |
| Schema drift — different tenants have different keys | Expected, but bites at reporting time         | Tools: `pg_jsonschema`, app-side validation, or pin "schema versions" inside the doc. |
| Wide rows fight TOAST                            | JSONB doc >2KB getting out-of-line stored         | Either accept (TOAST is fine for cold reads) or split hot fields into columns. |
| "Why is my customer doc 50KB?"                   | You embedded the orders inside the customer      | Split: orders is its own table with a FK.                    |

## Files

```
src/main/java/com/claude/dbpoc/m29/
├── Application.java
├── domain/
│   ├── Customer.java                  # tenant_id + email columns + profile jsonb
│   └── CustomerOrder.java             # customer_id FK + status + total + items jsonb
├── repo/
│   ├── CustomerRepository.java
│   └── CustomerOrderRepository.java
├── service/
│   └── HybridService.java             # seed, spine queries, leaf queries, lateral unnest, anti-pattern
└── web/
    └── HybridController.java          # /hybrid/{seed,customer,customers/with-tag,orders/with-sku,reports,profile,topology,anti-pattern}
src/main/resources/
├── application.yml                    # port 8229
└── schema.sql                         # customer + customer_order, both hybrid
```

## Related modules

- **[m28 - jsonb](../28-db-jsonb/)** — the JSONB primitives that this
  module composes. If you haven't internalized GIN vs functional vs
  CHECK, start there.
- **[m26 - materialized-view](../26-db-materialized-view/)** —
  expensive reports over `lateral jsonb_array_elements` are perfect
  MV candidates.
- **[m27 - cqrs](../27-db-cqrs/)** — outbox event payloads are JSONB
  on a row whose `aggregate_id` is a column. Same hybrid pattern.
- **[m30 - multi-tenancy](../30-db-multi-tenancy/)** — tenant-specific
  extension fields live in `profile` JSONB while `tenant_id` is the
  hard column that everything is indexed on. Hybrid is the
  multi-tenant storage shape.
```
