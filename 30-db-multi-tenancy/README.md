# 30 - DB Multi-Tenancy (shared schema + RLS, schema-per-tenant, DB-per-tenant)

> "How do you keep tenants from seeing each other's data?" is a
> three-way trade-off between **operational cost**, **blast radius**,
> and **how easy it is to make a mistake**. There is no right answer.
> There is the right answer for your tenant count, isolation
> requirements, and engineering bandwidth.

## Thesis

Three strategies, in increasing order of isolation and operational cost:

```
                             cheap                                 expensive
                       to operate                                  to operate
            ┌──────────────────────────────────────────────────────────────────┐
            │                                                                  │
            │  shared schema    schema-per-tenant         database-per-tenant   │
            │  + tenant_id      one DDL per tenant        one DB per tenant     │
            │  + RLS policy     same DB                                          │
            │                                                                  │
            └──────────────────────────────────────────────────────────────────┘
              weak isolation                                  strong isolation
              easiest to leak                              hardest to leak by accident
```

This module implements the first two against the same Postgres, on the
same domain (a `product` table). The third is conceptual — we discuss
it but don't run it.

| Strategy | Isolation | Migration cost | Per-tenant ops | Scale ceiling | Right when… |
|----------|-----------|---------------|---------------|---------------|-------------|
| Shared schema + tenant_id + **RLS** | App-policy enforced; superuser bypasses | One DDL applies to all tenants | None per-tenant; tenant rows just queries | Millions of tenants | You have lots of small tenants and analytics across them. |
| **Schema-per-tenant** | Structural; can't reach the wrong schema | DDL × N | Per-schema `pg_dump`, drop, restore | ~5-10k tenants before catalog pain | Mid-sized tenants, easy per-tenant backup/restore, compliance asks. |
| **DB-per-tenant** | Total | DDL × N | Per-DB everything | ~hundreds of large tenants | Hard isolation, per-tenant resource limits, regulated data. |

The hybrid pattern from m29 stacks naturally on any of these — every
strategy can have JSONB extension fields.

## Setup

```bash
cd ..
docker compose --profile postgres up -d
./mvnw -pl 30-db-multi-tenancy -am spring-boot:run
```

App boots on **:8230**. Two schemas in play:
- `m30_shared` — strategy 1 (one table, RLS on)
- `m30_t_1`, `m30_t_2`, `m30_t_3` — strategy 2 (one schema per tenant)

The tenant id is set per request via the **`X-Tenant-Id` header**.
The `TenantFilter` reads it into a `ThreadLocal` (`TenantContext`)
and the service layer uses it to either:
- `SET LOCAL app.tenant_id = ?` (shared schema → drives the RLS policy), or
- `SET LOCAL search_path = m30_t_? , public` (schema per tenant).

## Run order — Strategy 1: shared schema + RLS

```bash
# tenant 1 adds two products
curl -X POST 'localhost:8230/shared/product?sku=A1&name=A1&price=1.00' -H 'X-Tenant-Id: 1' | jq
curl -X POST 'localhost:8230/shared/product?sku=A2&name=A2&price=2.00' -H 'X-Tenant-Id: 1' | jq

# tenant 2 adds one product
curl -X POST 'localhost:8230/shared/product?sku=B1&name=B1&price=3.00' -H 'X-Tenant-Id: 2' | jq

# tenant 1 lists — sees ONLY tenant 1's rows
curl -s 'localhost:8230/shared/products' -H 'X-Tenant-Id: 1' | jq

# tenant 2 lists — sees ONLY tenant 2's rows
curl -s 'localhost:8230/shared/products' -H 'X-Tenant-Id: 2' | jq

# tenant 1 tries to read tenant 2's data EXPLICITLY. RLS blocks it.
curl 'localhost:8230/shared/breach/2' -H 'X-Tenant-Id: 1' | jq
#  → { "iAm":1, "triedToSee":2, "rowsLeaked":0, "verdict":"SAFE - RLS hid the rows" }

# diagnostic: rows visible vs total in pg_class
curl 'localhost:8230/shared/visibility' -H 'X-Tenant-Id: 1' | jq
```

## Run order — Strategy 2: schema per tenant

```bash
# tenants 1..3 already have schemas from schema.sql.
# Onboard a brand-new tenant 42:
curl -X POST 'localhost:8230/perschema/onboard/42' | jq

# tenant 1 adds two products into m30_t_1.product
curl -X POST 'localhost:8230/perschema/product?sku=X1&name=X1&price=11.00' -H 'X-Tenant-Id: 1' | jq
curl -X POST 'localhost:8230/perschema/product?sku=X2&name=X2&price=22.00' -H 'X-Tenant-Id: 1' | jq

# tenant 42 — different physical table.
curl -X POST 'localhost:8230/perschema/product?sku=Y1&name=Y1&price=99.00' -H 'X-Tenant-Id: 42' | jq

# Each tenant sees their own table only — search_path resolves
# unqualified `product` to their schema.
curl 'localhost:8230/perschema/products' -H 'X-Tenant-Id: 1'  | jq
curl 'localhost:8230/perschema/products' -H 'X-Tenant-Id: 42' | jq

# Across-all-tenants analytics in this strategy means scanning the
# catalog. Painful past a few thousand schemas.
curl 'localhost:8230/perschema/global-counts' | jq
```

## What each piece proves

### Row-Level Security — the "forgot to filter" seat belt

```sql
ALTER TABLE product ENABLE ROW LEVEL SECURITY;
ALTER TABLE product FORCE  ROW LEVEL SECURITY;

CREATE POLICY product_tenant_isolation ON product
  USING      (tenant_id = current_setting('app.tenant_id', true)::bigint)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::bigint);
```

`USING` filters on reads, `WITH CHECK` rejects writes to a different
tenant. The session variable `app.tenant_id` is set per transaction:

```java
@Transactional
List<Map<String,Object>> listProducts() {
    jdbc.execute("set local app.tenant_id = '" + TenantContext.require() + "'");
    return jdbc.queryForList("select * from product");   // no WHERE!
}
```

The query has **no** `WHERE tenant_id = ?`. The policy adds it. If
the engineer forgets the filter, the policy stops the leak.

**Caveats:**
- `FORCE ROW LEVEL SECURITY` makes the policy apply even to the table
  owner. Without it, the owner role bypasses RLS.
- The `appuser` role here is intentionally **not** a superuser and
  not `BYPASSRLS`. A superuser always bypasses RLS — be careful
  what role your application connects as.
- Indexes still work normally; the planner pushes the policy
  predicate into the scan and uses the `product_tenant_idx` index.

### Why the breach attempt returns 0

```bash
curl 'localhost:8230/shared/breach/2' -H 'X-Tenant-Id: 1'
# → rowsLeaked: 0
```

The SQL is literally `select count(*) from product where tenant_id = 2`.
The policy AND's `tenant_id = 1` into the predicate. The intersection
is empty, so the count is 0. **The application made a mistake; the
database caught it.** That's the entire value of RLS over
app-only-enforced filters.

### Schema-per-tenant — `set local search_path`

```java
jdbc.execute("set local search_path = m30_t_" + TenantContext.require() + ", public");
```

`SET LOCAL` clears at commit, so the connection pool is safe even
without per-request reset code. The unqualified `product` in
subsequent SQL resolves to the tenant's schema.

This is more invasive in a JPA world — Hibernate's default schema is
set at SessionFactory boot. For schema-per-tenant with full
Hibernate support, see `hibernate.multiTenancy=SCHEMA` plus a
`CurrentTenantIdentifierResolver` and `MultiTenantConnectionProvider`.
This POC uses raw JDBC to keep the mechanic obvious.

### Why cross-tenant analytics is painful here

The "global product count" endpoint reads from `pg_class` — it can't
just `select count(*) from product` because there's no one `product`
table. The alternatives:

```sql
-- option A: dynamic UNION ALL
SELECT * FROM m30_t_1.product
UNION ALL SELECT * FROM m30_t_2.product
UNION ALL SELECT * FROM m30_t_3.product
UNION ALL ... ;  -- generated from the catalog at query time
```

```sql
-- option B: a periodic ETL that copies all tenant tables into
-- one analytics table with tenant_id added. CQRS / m27 territory.
```

Either way you pay for the strategy's structural isolation in
analytics ergonomics. The shared-schema strategy answers the same
question with `SELECT COUNT(*) FROM product GROUP BY tenant_id`.

## Strategy 3 (conceptual): database per tenant

We don't run a separate Postgres per tenant in this POC, but the
shape:

```
   ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
   │  tenant_1 db     │  │  tenant_2 db     │  │  tenant_3 db     │
   │  separate user   │  │  separate user   │  │  separate user   │
   │  separate roles  │  │  separate roles  │  │  separate roles  │
   │  separate WAL    │  │  separate WAL    │  │  separate WAL    │
   └──────────────────┘  └──────────────────┘  └──────────────────┘
            │                     │                     │
            └─────────────────────┴─────────────────────┘
                                  │
                          ┌───────────────┐
                          │  routing app  │
                          │  picks DB     │
                          │  per request  │
                          └───────────────┘
```

When to choose this:

- **Hard isolation guarantees** — regulated data, contractual.
- **Per-tenant resource limits** — one tenant can't starve another
  on CPU/IO, because they're in different processes.
- **Per-tenant Postgres tuning** — different settings, different
  extensions, different replicas.
- **Per-tenant version skew** — tenant A on PG15, tenant B on PG16
  during a phased upgrade.

What you pay:

- N times the operational cost of one Postgres.
- Cross-tenant queries are now cross-database — federated, slow,
  often via materialized exports.
- Connection pool fragmentation: one pool per tenant DB.
- The schema migration tool has to run N times.

In Spring this is **`AbstractRoutingDataSource`** keyed by tenant id,
the same mechanic as the shard router in **m23-db-sharding**. From a
code perspective, sharding by tenant and database-per-tenant are the
same Java; only the deployment topology differs.

## Choosing

A flowchart you can keep in your head:

```
   Do you need hard isolation (regulated / contractual)?
      └── yes ──> database-per-tenant.
      └── no  ──> next question.

   Do you have >5,000 tenants?
      └── yes ──> shared schema + RLS.
      └── no  ──> next question.

   Do you need per-tenant backup/restore as a feature?
      └── yes ──> schema-per-tenant.
      └── no  ──> shared schema + RLS.

   Do you run heavy analytics across tenants?
      └── yes ──> push toward shared schema + RLS, OR
                  build an ETL into a shared analytics table.
```

## Production checklist

| Symptom                                          | Likely cause                                | Fix                                                                |
|--------------------------------------------------|---------------------------------------------|--------------------------------------------------------------------|
| Tenant sees another tenant's rows                | RLS not enabled / not forced; superuser conn | `ENABLE` + `FORCE` RLS; connect as non-`BYPASSRLS` role.            |
| Insert with wrong tenant_id silently lands       | Missing `WITH CHECK` on the policy          | Add a `WITH CHECK` clause that mirrors the `USING` predicate.       |
| `pg_dump --schema=m30_t_99` works but is huge    | Per-tenant schema strategy is fine; data grew | Partition the tenant's tables (m22) or graduate to DB-per-tenant.   |
| Catalog (`pg_class`) bloated, planner slow       | Schema-per-tenant past ~10k tenants         | Migrate to shared-schema + RLS, or shard tenants across clusters.   |
| Cross-tenant report runs forever                 | UNION ALL across many schemas               | Build a flattened analytics table via outbox (m27).                 |
| Migration script ran on tenant 1, missed 2..N    | Bespoke per-schema migration                | Use a runner: `for s in m30_t_%; run migration.sql with search_path=s`. |
| Connection pool exhausted across tenants         | One pool per tenant DB (strategy 3)         | Shared connection-pool proxy (PgBouncer), per-tenant routing on top. |
| Forgot `set local` → tenant leaks across requests | Used `SET` instead of `SET LOCAL`           | Always `SET LOCAL`, scope to the tx. Audit every place.            |

## Files

```
src/main/java/com/claude/dbpoc/m30/
├── Application.java
├── tenant/
│   ├── TenantContext.java               # ThreadLocal<Long>
│   └── TenantFilter.java                # reads X-Tenant-Id header
├── service/
│   ├── SharedSchemaService.java         # strategy 1: SET LOCAL app.tenant_id; RLS does the rest
│   └── SchemaPerTenantService.java      # strategy 2: SET LOCAL search_path
└── web/
    └── TenancyController.java           # /shared/*, /perschema/*
src/main/resources/
├── application.yml                      # port 8230
└── schema.sql                           # m30_shared (RLS policy) + m30_t_{1,2,3}
```

## Related modules

- **[m21 - read-replica](../21-db-read-replica/)** — strategy 1's
  read path is replica-friendly if you propagate the `app.tenant_id`
  session var to the replica connection. Strategy 3 has one replica
  per tenant DB — gets expensive fast.
- **[m22 - table-partitioning](../22-db-table-partitioning/)** — a
  shared-schema multi-tenant table is a classic LIST PARTITION BY
  `tenant_id` candidate when one tenant is 50% of the data.
- **[m23 - sharding](../23-db-sharding/)** — sharding by tenant id
  IS database-per-tenant at the engineering layer. The shard router
  is the tenant router.
- **[m27 - cqrs](../27-db-cqrs/)** — the read model is where
  cross-tenant analytics actually wants to live, leaving the write
  model strictly tenant-isolated.
- **[m29 - hybrid-relational-document](../29-db-hybrid-relational-document/)**
  — per-tenant extension fields go in JSONB on the shared-schema
  table. Best of both.
```
