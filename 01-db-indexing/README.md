# 01 — DB Indexing (Postgres 16)

**What this module proves:** the same `SELECT` can be 100x faster or slower depending on whether you have the right index — and "the right index" is not always "a B-tree on the column in the WHERE clause." This POC seeds a 1M-row `events` table, then lets you toggle B-tree, composite, covering, partial, functional, and GIN-trigram indexes via REST and see the `EXPLAIN (ANALYZE, BUFFERS)` plan plus a 20-run average for each.

## Setup

```bash
# from the repo root
./scripts/setup.sh postgres
./mvnw -pl 01-db-indexing -am spring-boot:run
```

App listens on **`http://localhost:8201`**. The `events` table is created automatically by `schema.sql` on boot; no indexes are created until you ask for them.

## Workflow

```bash
# 1. Seed 1M rows (takes ~30-90s depending on hardware)
curl -X POST 'http://localhost:8201/seed?rows=1000000'

# 2. For each index pattern below, run:
#    a. baseline bench         GET  /bench/<x>
#    b. create the index       POST /indexes/<x>
#    c. bench again            GET  /bench/<x>
#    d. (optional) clean up    DELETE /indexes
#
# Each /bench response includes the parsed EXPLAIN plan, a one-line summary
# ("Index Only Scan using ... actual=0.123ms"), and a 20-run avg in microseconds.

# 3. Inspect what's currently indexed and how big each one is.
curl -s 'http://localhost:8201/indexes' | jq
```

## The five lessons

### 1. B-tree — and when Seq Scan beats it

```bash
curl -s 'http://localhost:8201/bench/btree?userId=42' | jq .planSummary
curl -X POST 'http://localhost:8201/indexes/btree'
curl -s 'http://localhost:8201/bench/btree?userId=42' | jq .planSummary
# Before: Seq Scan, ~80ms avg.
# After : Index Scan using idx_events_user_id, ~0.1ms avg.

# The flip side — low selectivity defeats the index. PAGE_VIEW = ~50% of rows:
curl -s 'http://localhost:8201/bench/btree-low-selectivity?eventType=PAGE_VIEW' | jq .planSummary
# Even with an index on event_type, the planner correctly picks Seq Scan:
# reading half the heap sequentially is cheaper than half a million index hops.
```

### 2. Composite column order matters

```bash
# Query: WHERE user_id = 42 (ORDER BY created_at DESC is just incidental).
# (user_id, created_at DESC)  -> works: leading column matches WHERE
curl -X POST 'http://localhost:8201/indexes/composite-good'
curl -s 'http://localhost:8201/bench/btree?userId=42' | jq .planSummary

curl -X DELETE 'http://localhost:8201/indexes'

# (created_at DESC, user_id)  -> useless: leading column unrelated to WHERE
curl -X POST 'http://localhost:8201/indexes/composite-bad'
curl -s 'http://localhost:8201/bench/btree?userId=42' | jq .planSummary
# Planner falls back to Seq Scan — the "bad" index is invisible to this query.
```

### 3. Covering index → Index Only Scan, `Heap Fetches: 0`

```bash
curl -X DELETE 'http://localhost:8201/indexes'
curl -X POST 'http://localhost:8201/indexes/covering'
curl -s 'http://localhost:8201/bench/covering?userId=42' | jq '.planChain'
# Look for:  -> Index Only Scan [idx_events_user_covering] ... heap_fetches=0
# The INCLUDE (status, amount) columns ride along in the leaf pages, so
# Postgres never has to visit the heap. This is the cheapest possible read.
```

### 4. Partial index — small, focused, fast

```bash
curl -X DELETE 'http://localhost:8201/indexes'
curl -s 'http://localhost:8201/bench/partial' | jq .planSummary   # Seq Scan, slow
curl -X POST 'http://localhost:8201/indexes/partial'
curl -s 'http://localhost:8201/indexes' | jq '.[] | {name, size_pretty}'
# The partial index covers only the ~2% PENDING rows -> typically ~1/50 the
# size of a full B-tree on the same column. Check size_pretty for proof.
curl -s 'http://localhost:8201/bench/partial' | jq .planSummary
# Index Scan using idx_events_pending_partial, tiny actual time.
```

### 5. Functional index — `LOWER(col)` defeats a normal B-tree

```bash
curl -X DELETE 'http://localhost:8201/indexes'
# First, prove that a *plain* B-tree on search_text doesn't help the query:
curl -X POST 'http://localhost:8201/indexes/plain-text'
curl -s 'http://localhost:8201/bench/functional?q=Foo' | jq .planSummary
# Seq Scan — the planner can't use a B-tree on search_text when the WHERE
# clause wraps the column in LOWER().

curl -X DELETE 'http://localhost:8201/indexes'
curl -X POST 'http://localhost:8201/indexes/functional'
curl -s 'http://localhost:8201/bench/functional?q=Foo' | jq .planSummary
# Index Scan using idx_events_lower_search_text — the function-based index
# matches the function in the predicate.
```

### 6. GIN trigram — the only sane way to do `LIKE '%foo%'` at scale

```bash
curl -X DELETE 'http://localhost:8201/indexes'
curl -s 'http://localhost:8201/bench/gin-trigram?q=foo' | jq .planSummary   # Seq Scan
curl -X POST 'http://localhost:8201/indexes/gin-trigram'
curl -s 'http://localhost:8201/bench/gin-trigram?q=foo' | jq .planSummary
# Bitmap Index Scan on idx_events_search_trgm — `ILIKE '%foo%'` becomes a
# trigram lookup. No other index type can handle a leading wildcard.
```

## Endpoint reference

| Method  | Path                              | Notes                                        |
| ------- | --------------------------------- | -------------------------------------------- |
| `POST`  | `/seed?rows=N`                    | TRUNCATE + bulk insert + ANALYZE             |
| `GET`   | `/indexes`                        | list current indexes with on-disk size       |
| `DELETE`| `/indexes`                        | drop all module-owned indexes                |
| `POST`  | `/indexes/btree`                  | `(user_id)`                                  |
| `POST`  | `/indexes/composite-good`         | `(user_id, created_at DESC)`                 |
| `POST`  | `/indexes/composite-bad`          | `(created_at DESC, user_id)` — wrong order   |
| `POST`  | `/indexes/covering`               | `(user_id) INCLUDE (status, amount)`         |
| `POST`  | `/indexes/partial`                | `WHERE status = 'PENDING'`                   |
| `POST`  | `/indexes/plain-text`             | baseline `(search_text)` — for lesson 5      |
| `POST`  | `/indexes/functional`             | `(LOWER(search_text))`                       |
| `POST`  | `/indexes/gin-trigram`            | `USING GIN (search_text gin_trgm_ops)`       |
| `GET`   | `/bench/btree?userId=N`           | `WHERE user_id = ?`                          |
| `GET`   | `/bench/btree-low-selectivity`    | `WHERE event_type = 'PAGE_VIEW'` (~50%)      |
| `GET`   | `/bench/covering?userId=N`        | projects only covered columns                |
| `GET`   | `/bench/partial`                  | PENDING + ORDER BY created_at + LIMIT 50     |
| `GET`   | `/bench/functional?q=Foo`         | `LOWER(search_text) = LOWER(?)`              |
| `GET`   | `/bench/gin-trigram?q=foo`        | `search_text ILIKE '%?%'`                    |

All `/bench/*` responses include: `planSummary`, `planChain`, `rawPlan` (full JSON), and a `timing` block with avg/min/max in microseconds over 20 runs.
