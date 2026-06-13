# 28 - DB JSONB (binary JSON, GIN indexing, the schemaless anti-pattern)

> JSONB is the right shape for **fields you don't know in advance**.
> It is the wrong shape for fields you **do**. The trouble is that
> "schemaless" feels free, so teams reach for it on fields they know
> exactly — and discover, six months in, that every constraint they
> didn't write is a bug in production.

## Thesis

Postgres `jsonb` is binary-stored, indexable JSON. Inserts are slightly
slower than relational columns (we parse and rewrite the document);
reads of specific paths are slightly slower (we have to walk the
binary tree); but containment queries (`@>`) and key existence (`?`)
get GIN indexes that are surprisingly fast.

The temptation: "put everything in a `data jsonb` column and we never
need migrations again." The reality:

| What relational gives you for free | What JSONB makes you re-invent |
|------------------------------------|---------------------------------|
| Column types (price is a number)   | `CHECK (jsonb_typeof(data->'price') = 'number')` per field |
| `NOT NULL`                         | Manual JSON Schema, or per-field CHECK |
| Foreign keys                       | None. Reference integrity is on you. |
| Compile-time column existence      | Misspelled keys are silently NULL at read time |
| Per-column statistics              | Planner guesses cardinality from the JSONB blob |
| Cheap `ALTER` (sometimes)          | Cheap schema evolution (always) ← **the one real win** |

The right read on JSONB: **use it where the shape genuinely varies**
(extension fields, polymorphic payloads, audit snapshots, outbox
event payloads). Don't use it where the shape is fixed and known —
columns are still the better tool.

```
   ┌────────────────────────────────────────────────────────────┐
   │  product_normalized                                         │
   │    id sku name brand price stock category                   │
   │   indexes: pn_brand_idx, pn_category_idx                    │
   │   → b-tree index scans, type-safe, constraint-checked       │
   └────────────────────────────────────────────────────────────┘
                                vs
   ┌────────────────────────────────────────────────────────────┐
   │  product_doc                                                │
   │    id   data:jsonb                                          │
   │   indexes:                                                  │
   │     gin(data)                ← @> ? ?| ?& and key lookups   │
   │     gin(data jsonb_path_ops) ← @> only, ~half the size      │
   │     btree((data->>'sku'))    ← functional, for one path     │
   │   CHECK (jsonb_typeof(data->'sku')   = 'string')            │
   │   CHECK (jsonb_typeof(data->'price') = 'number')            │
   └────────────────────────────────────────────────────────────┘
```

## Setup

```bash
cd ..
docker compose --profile postgres up -d
./mvnw -pl 28-db-jsonb -am spring-boot:run
```

App boots on **:8228**. Schema `m28_jsonb`. Two tables hold the same
domain — pick your fighter.

## Run order

```bash
# 1. Seed both tables with the same 10k rows.
curl -X POST 'localhost:8228/jsonb/seed?n=10000' | jq

# 2. Build the GIN indexes. Both flavors, because the comparison is the point.
curl -X POST localhost:8228/jsonb/gin | jq

# 3. Containment query against the doc side. Look at the plan — Bitmap Index Scan on pd_data_gin.
curl localhost:8228/jsonb/by-brand/acme | jq

# 4. Same logical query against the normalized side. B-tree Index Scan on pn_brand_idx.
curl localhost:8228/jsonb/by-brand-norm/acme | jq

# 5. Apples-to-apples timing.
curl 'localhost:8228/jsonb/bench?brand=acme&iters=1000' | jq
#    → docPerCallUs typically 1.5-3x normalizedPerCallUs.

# 6. The path-query story: data->>'sku' = ? doesn't use a GIN.
#    Without the functional index, plan is a Seq Scan.
curl localhost:8228/jsonb/by-sku/SKU-0001234 | jq

# 7. Add the functional index — now it's a B-tree scan.
curl -X POST localhost:8228/jsonb/functional | jq
curl localhost:8228/jsonb/by-sku/SKU-0001234 | jq

# 8. The anti-pattern. Try to insert garbage into both tables.
#    Normalized refuses 3/3. JSONB refuses 1/3.
curl -X POST localhost:8228/jsonb/anti-pattern | jq
```

## What each piece proves

### `seed` — same data, two shapes

Both tables get the same 10k products. The `data` column in
`product_doc` ALSO carries a `warranty: {months, type}` sub-object that
doesn't exist in the normalized table. That's the genuine JSONB win:
you can add that field today without a migration.

```sql
-- this is free
update product_doc set data = data || '{"discontinued": true}' where id=42;
-- the normalized equivalent requires:
alter table product_normalized add column discontinued boolean default false;
-- and on a 50M-row table that's an outage.
```

But notice the `pg_total_relation_size` in the response — the JSONB
table is **bigger on disk** because every row stores every key. The
relational form stores keys once in the catalog.

### `createGinIndexes` — two flavors, pick one

```sql
CREATE INDEX pd_data_gin       ON product_doc USING gin (data);
CREATE INDEX pd_data_path_gin  ON product_doc USING gin (data jsonb_path_ops);
```

| Operator | `gin(data)` | `gin(data jsonb_path_ops)` |
|----------|-------------|----------------------------|
| `@>` containment | yes | **yes** |
| `?` key exists | yes | no |
| `?\|` any-key exists | yes | no |
| `?&` all-keys exist | yes | no |
| `@?` jsonpath | yes (PG 12+) | no |
| Size | ~2x | **~1x** |
| Query speed | baseline | **faster on `@>`** |

**Default to `jsonb_path_ops`** if `@>` is the only operator you use.
Switch to the broader `gin(data)` only when you need key existence.

### Containment query

```sql
SELECT * FROM product_doc WHERE data @> '{"brand":"acme"}';
```

`data @> '{"brand":"acme"}'` reads "data contains this fragment".
With the GIN index, the planner uses a Bitmap Index Scan and is
roughly within 2-3x of the normalized B-tree index scan. **Without**
the GIN, it's a Seq Scan and 50-100x slower on this dataset.

The `note` field in the response will point you at the right line of
the EXPLAIN ANALYZE plan.

### Path query → functional index

```sql
SELECT * FROM product_doc WHERE data->>'sku' = 'SKU-0001234';
```

This is the foot-shoot: `->>` extracts the value as text, GIN doesn't
help, you get a Seq Scan. The fix:

```sql
CREATE INDEX pd_sku_fn ON product_doc ((data->>'sku'));
```

A B-tree on the expression. Now `WHERE data->>'sku' = ?` plans to an
Index Scan. **The expression in the index must match the WHERE clause
byte-for-byte** — `data->'sku'` (jsonb) and `data->>'sku'` (text) are
different and need different indexes.

### `benchmark` — the actual cost of flexibility

Run `/jsonb/bench?brand=acme&iters=1000` after seeding and creating
the GIN. On a typical machine you'll see:

```
normalizedPerCallUs:   ~60-120 µs
docPerCallUs:         ~150-300 µs
ratioDocOverNormalized: ~2.0-3.0
```

That ratio is the cost of JSONB for the **best case** (indexed,
small dataset, warm cache). It widens as data grows.

### `antiPatternDemo` — what JSONB lets through

Three invalid writes:

| Write                       | normalized | doc                                |
|-----------------------------|------------|------------------------------------|
| `stock = -1`                | blocked    | **accepted** (no check for stock)  |
| key `"skk"` instead of sku  | blocked at compile time | **accepted** silently |
| `price = "free"` (string)   | type error | blocked (we wrote the check)       |

The normalized form catches all three for free. The JSONB form caught
the one we remembered to write a CHECK for. **Every other constraint
is a bug waiting to happen.**

Real-world projects solve this with one of:

- **`pg_jsonschema`** extension — full JSON Schema validation in a
  CHECK constraint. Best-in-class if you can install the extension.
- **A runtime validation layer** (Jackson + Bean Validation, or
  json-schema-validator) — runs on the app side before write.
- **A custom function-based CHECK** for every field you care about.
  Tedious; what we did here for `sku` and `price`.

None of these are free. They're the cost of being schemaless in a
relational engine.

## When JSONB is the right call

| Use case                                | Why JSONB wins                 |
|-----------------------------------------|--------------------------------|
| Outbox event payloads (see m27)         | Per-event-type shapes; you don't want a table per event. |
| Audit / snapshot logs                   | You're storing what an entity looked like at time T; the shape may evolve. |
| Extension / custom fields per tenant    | Tenant A wants 3 fields, B wants 7; no migration per tenant. |
| Polymorphic webhook payloads            | Inbound JSON you don't control. |
| Sparse attributes                       | 200 possible fields, any given row has 5. |
| Caching denormalized API responses      | The shape IS the API. |

## When JSONB is the wrong call

| Use case                                | Why columns win                |
|-----------------------------------------|--------------------------------|
| Core domain entities with known fields  | Constraints, types, FKs, statistics. |
| Anything with referential integrity     | JSONB has no FK story. |
| Anything you'll sort by                 | GIN doesn't help ORDER BY; functional index or column needed. |
| Hot-path read fields                    | `->>` extraction is real work; a column is free. |
| Anything financial                      | `numeric(18,2)` vs `jsonb_typeof = 'number'` — one is exact, one is a JSON number. |

## Production checklist

| Symptom                                  | Likely cause                             | Fix                                                                |
|------------------------------------------|------------------------------------------|--------------------------------------------------------------------|
| `@>` query is slow                       | No GIN index on the JSONB column         | `CREATE INDEX ... USING gin (col jsonb_path_ops);`                 |
| `->>` query is slow                      | GIN doesn't help; need functional index  | `CREATE INDEX ... ON tbl ((col->>'field'));`                       |
| GIN index is huge                        | Using default `gin(col)` when `@>` is the only op | Switch to `gin(col jsonb_path_ops)`. ~half the size.       |
| Table is 3x bigger than expected        | Repeated keys per row, no TOAST compression hit | Shorter keys, or normalize hot fields into columns.          |
| Misspelled key returns NULL silently     | No schema validation                     | `pg_jsonschema`, app-side validation, or per-field CHECK.          |
| Constraints from spec not enforced       | Forgot the CHECK constraint              | Audit every "must be X" and add a CHECK or pg_jsonschema rule.     |
| Planner picks Seq Scan over GIN          | Tiny table; statistics off; expression mismatch | Confirm with EXPLAIN; `ANALYZE` the table; check expression literal. |
| Hot reads slower after going JSONB       | Each read parses the binary doc          | Move hot fields to real columns; keep `data` for the rest.         |

## Files

```
src/main/java/com/claude/dbpoc/m28/
├── Application.java
├── domain/
│   ├── ProductNormalized.java            # boring relational shape
│   └── ProductDoc.java                   # @JdbcTypeCode(SqlTypes.JSON) on data
├── repo/
│   ├── ProductNormalizedRepository.java
│   └── ProductDocRepository.java
├── service/
│   └── JsonbService.java                 # seed, GIN, functional, query, benchmark, anti-pattern
└── web/
    └── JsonbController.java              # /jsonb/{seed,gin,functional,by-brand,by-sku,bench,anti-pattern}
src/main/resources/
├── application.yml                       # port 8228, m28_jsonb schema
└── schema.sql                            # two tables, two checks
```

## Related modules

- **[m26 - materialized-view](../26-db-materialized-view/)** — MV
  payloads are often `jsonb` for the same reason; this module's
  index guidance applies.
- **[m27 - cqrs](../27-db-cqrs/)** — `outbox_events.payload` is
  `jsonb`. That's the canonical right use of JSONB.
- **[m29 - hybrid-relational-document](../29-db-hybrid-relational-document/)**
  — picks up where this one stops: when you want SOME columns
  relational and SOME JSONB on the same row.
- **[m30 - multi-tenancy](../30-db-multi-tenancy/)** — tenant-specific
  extension fields are a textbook JSONB use case.
```
