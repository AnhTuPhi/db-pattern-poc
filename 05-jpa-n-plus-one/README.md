# 05 - JPA N+1 and Five Fixes Side-by-Side

> The JPA bug in every codebase. This POC shows the disease and five cures, each behind its own HTTP endpoint, with the actual SQL statement count surfaced in the JSON response.

## Why this matters

N+1 is the single most common JPA pathology in production code. A naive
`orderRepo.findAll()` followed by a loop over `order.getItems()` looks innocent
in code review — but it fires **1 + N** queries against the database. With 100
orders that's 101 round-trips for what should be one query. With 10,000 orders
it's a P95 spike that wakes someone up at 03:00.

The hard part isn't fixing it once you've spotted it — it's knowing **which fix
fits the situation**. JOIN FETCH, EntityGraph, @BatchSize, DTO projection, and
the second-level cache all eliminate N+1, but they trade off differently
against pagination, multi-collection fetches, write throughput, and cache
invalidation cost.

This POC runs all of them against the same dataset and returns a single
table-shaped JSON so you can see the cost of each, in statements, in one screen.

## Setup

Requires the Postgres container from the project root:

```bash
cd ..
docker compose --profile postgres up -d
```

Then build and run module 05:

```bash
./mvnw -pl 05-jpa-n-plus-one -am spring-boot:run
```

App boots on **:8205**.

## Run order

```bash
# 1. Seed 100 orders × 5 items each (= 500 child rows).
curl -X POST 'localhost:8205/seed?orders=100&itemsPerOrder=5'

# 2. The headline call — side-by-side comparison of all six variants.
curl 'localhost:8205/compare/all?orders=100' | jq

# Or hit individual variants:
curl 'localhost:8205/demo/naive'              | jq
curl 'localhost:8205/demo/join-fetch'         | jq
curl 'localhost:8205/demo/entity-graph'       | jq
curl 'localhost:8205/demo/batch-size?size=20' | jq
curl 'localhost:8205/demo/dto-projection'     | jq
curl 'localhost:8205/demo/second-level-cache' | jq
```

Each response has the same shape:

```json
{
  "variant": "naive",
  "ordersFetched": 100,
  "itemsTotal": 1500,
  "sqlStatements": 101,
  "elapsedMs": 142.3,
  "verdict": "Classic N+1: 1 query for orders + 1 per order = 1 + N statements."
}
```

`sqlStatements` is the number to watch.

## Expected SQL counts (with N=100, M=5)

| Variant                | SQL count | When to use it                                          | Don't use it when...                              |
|------------------------|-----------|---------------------------------------------------------|---------------------------------------------------|
| `naive`                | **101**   | Never on a list endpoint                                | You care about latency                            |
| `join-fetch`           | **1**     | Single-collection fetch, no pagination                  | You need pagination, or 2+ collection fetches     |
| `entity-graph`         | **1**     | Fixed graph reused across queries                       | The graph changes per call (use JOIN FETCH)       |
| `batch-size` (size=20) | **6**     | Lazy collections you don't always touch                 | You always touch the collection (JOIN FETCH wins) |
| `dto-projection`       | **1**     | Read-heavy paths, list views, exports                   | You need to mutate and save the entities back     |
| `second-level-cache`   | **~1**\*  | Reference data, read-mostly entities                    | Write-heavy entities, frequent invalidation       |

\* On the warm pass. The cold pass equals `naive`.

## Caveats (read before reaching for a fix)

### JOIN FETCH + pagination = wrong results
Adding `Pageable` to a JOIN FETCH on a `@OneToMany` triggers
`HHH000104: firstResult/maxResults specified with collection fetch; applying in memory!`
— Hibernate loads the *entire* table and paginates in RAM. Defeats the
purpose of pagination. Use a two-step fetch: page IDs first, then load
items by ID.

### Multiple JOIN FETCH on collections = cartesian product
JOIN FETCH on two `@OneToMany` collections (e.g. `items` AND `payments`)
multiplies the row count: 5 items × 3 payments = 15 raw rows per order.
Hibernate may throw `MultipleBagFetchException` if both are `List`s. Either
fetch one and let the other be a `@BatchSize` lazy, or split into two
queries.

### `@EntityGraph` vs JOIN FETCH
EntityGraph is cleanest when the fetch plan is fixed — the relationship
to load is declared as data, not strings. JOIN FETCH wins for dynamic
queries where the plan depends on caller flags.

### `@BatchSize` (or `default_batch_fetch_size`) is a great default
If you don't know in advance which collections each caller will touch
(common in service classes that are reused across endpoints), setting
`hibernate.default_batch_fetch_size=20` (or similar) turns every
unavoidable lazy load into an IN-clause batch instead of one query per
parent. You give up perfect optimisation for crash insurance.

### DTO projection is the only fix that *cannot* trigger N+1
There's no managed entity to lazy-load from. For read-heavy paths
(list views, exports, dashboards), this is the right answer — no
JPA-specific knowledge needed downstream, and the SQL is hand-tunable.

### L2 cache: right tool, wrong job
The second-level cache eliminates N+1 on the *second* fetch only; the
first one still hits the disease. It's the right answer for reference
data (countries, currencies, product catalogues) that change rarely.
It's the wrong answer for write-heavy entity graphs — the invalidation
cost on every mutation eats the read savings.

## How `sqlStatements` is counted

`com.claude.dbpoc.common.SqlCounter` wraps the auto-configured DataSource
via a `BeanPostProcessor`. Every `executeXxx` call on the JDBC layer
increments an `AtomicLong`. Each demo endpoint calls `sqlCounter.reset()`
at the start and returns `sqlCounter.getStatementCount()` at the end.

That number is the **physical SQL round-trip count** — the same thing
that shows up in `pg_stat_statements` and in production APM traces.
This is the metric that decides whether your service survives.

## Files

```
src/main/java/com/claude/dbpoc/m05/
├── Application.java
├── DataSourceConfig.java          # SqlCounter wrapping via BeanPostProcessor
├── domain/
│   ├── Order.java                  # @OneToMany LAZY (sets up the N+1 demo)
│   └── Item.java                   # @Cache for the L2 demo
├── repo/
│   └── OrderRepository.java        # JOIN FETCH, EntityGraph, DTO query
├── dto/
│   ├── OrderSummaryDto.java        # projection target
│   ├── OrderResponse.java
│   └── DemoResult.java             # the JSON shape every endpoint returns
├── service/
│   └── DemoService.java            # the six variants, each @Transactional
└── controller/
    ├── SeedController.java         # POST /seed
    ├── DemoController.java         # GET /demo/{variant}
    └── CompareController.java      # GET /compare/all  ← headline
```

## Module 06 preview

If you forget `@Transactional` on the demo service methods, you'll get
`LazyInitializationException` instead of N+1 — that whole class of bugs
is the subject of module 06 (`06-jpa-fetch-strategies`).
