# DB Production Patterns POC

A multi-module Spring Boot 3.4 / Java 21 reference project for the database and JPA patterns that decide whether your service survives production. Built against **Oracle 23ai Free** (matches the prod stack) and **Postgres 16** (better diagnostic tooling for some lessons), with **JPA**, **QueryDSL**, and **p6spy** for SQL visibility.

Each module is a runnable Spring Boot app focused on one pattern. The code is heavily commented to explain *why* each setting matters, not just what it does.

---

## Documentation map

Start here depending on what you need:

| Doc | Read it when you want… |
|---|---|
| **[ISSUE.md](ISSUE.md)** | The problem statement — *why* this repo exists, the ten problem families, the stakes, scope, and definition of done. |
| **[TECHNICAL.md](TECHNICAL.md)** | The per-module dissection — for each pattern: the hard problem, what it protects, the solution shape, key tech by responsibility, how it solves each sub-problem, and the **tech debt to acknowledge**. |
| **[CONSISTENCY.md](CONSISTENCY.md)** | What horizontal scaling (k8s pods / VM fleet) does to each pattern — which ones are 🟢 pod-count-neutral, which are 🟡 need care, and which 🔴 silently break from 1 replica to N. |
| **[docs/explainer.html](docs/explainer.html)** | An interactive, filterable visual walkthrough — the request/data flow, all 30 modules as cards, and the scaling-impact matrix. Open it in any browser (no server needed). |
| **README.md** (this file) | How to build, the infra profiles, and per-module curl walkthroughs. |

---

## TL;DR — start everything

```bash
./scripts/setup.sh postgres                     # Just Postgres (~30s)
./scripts/setup.sh core                         # Postgres + Oracle (Oracle takes ~3min first time)
./mvnw clean install -DskipTests                # Build all modules

# Run any module:
./mvnw -pl 05-jpa-n-plus-one spring-boot:run
```

| Module | Port | Pattern | DB |
|---|---|---|---|
| 01-db-indexing | 8201 | B-tree / covering / partial / functional / GIN | Postgres |
| 02-db-query-plan | 8202 | EXPLAIN ANALYZE workflow, plan shapes | Postgres + Oracle |
| 03-db-slow-query-hunting | 8203 | `pg_stat_statements` / `V$SQL` top-N hunter | Postgres + Oracle |
| 04-db-cardinality-estimation | 8204 | Stale stats → bad plans, extended statistics | Postgres |
| 05-jpa-n-plus-one | 8205 | The N+1 bug + 5 fixes side-by-side | Postgres |
| 06-jpa-fetch-strategies | 8206 | Eager vs lazy, LIE, OSIV, projections | Postgres |
| 07-jpa-flush-and-cascade | 8207 | cascade=ALL, orphanRemoval, flush modes | Postgres |
| 08-jpa-batch-insert-update | 8208 | `hibernate.jdbc.batch_size`, `order_inserts` | Postgres + Oracle |
| 09-querydsl-vs-jpql-vs-criteria | 8209 | Same query, 4 APIs compared | Postgres |
| 10-db-isolation-levels | 8210 | Dirty / non-repeatable / phantom / lost update | Postgres |
| 11-db-locking | 8211 | Row/table/intention/gap locks, `FOR UPDATE SKIP LOCKED` job queue | Postgres |
| 12-db-deadlock | 8212 | Reproduce, read the deadlock graph, fix with lock ordering + retry | Postgres |
| 13-db-optimistic-vs-pessimistic | 8213 | `@Version` vs `SELECT FOR UPDATE` under contention, hot-row problem | Postgres |
| 14-db-long-transaction | 8214 | Long tx = lock hold + MVCC bloat + replication lag + undo growth | Postgres |
| 15-db-connection-pool | 8215 | HikariCP tuning, pool sizing math, `maxLifetime`, `connectionTimeout` | Postgres |
| 16-db-connection-leak | 8216 | Leak detection, `leakDetectionThreshold`, recovery | Postgres |
| 17-db-pool-exhaustion | 8217 | Cascading timeout, retry storm, bulkhead per-tenant/per-feature | Postgres |
| 18-db-zero-downtime-migration | 8218 | Expand/contract pattern, dual-write/dual-read column changes | Postgres |
| 19-db-online-ddl | 8219 | `pg_repack`, `gh-ost`, Oracle online redefinition | Postgres + Oracle |
| 20-db-migration-rollback | 8220 | Flyway/Liquibase, "always-forward" pattern, versioned vs repeatable | Postgres |
| 21-db-read-replica | 8221 | Primary/replica routing, replica lag, read-your-writes | Postgres |
| 22-db-table-partitioning | 8222 | RANGE/LIST/HASH + partition pruning + sliding-window retention | Postgres |
| 23-db-sharding | 8223 | App-level sharding by tenant; modulo vs consistent hashing; scatter-gather | Postgres |
| 24-db-vitess-citus | 8224 | Distributed Postgres via Citus: distributed / co-located / reference tables | Postgres (Citus) |
| 25-db-caching-layers | 8225 | Hibernate L1, Caffeine (L2 in-proc), Redis (L2 shared); stampede + single-flight | Postgres + Redis |
| 26-db-materialized-view | 8226 | Pre-aggregated reads, `REFRESH CONCURRENTLY`, trigger-maintained computed columns | Postgres |
| 27-db-cqrs | 8227 | Write model + outbox + poller projecting into a disposable read model | Postgres |
| 28-db-jsonb | 8228 | JSONB + GIN (`jsonb_path_ops` vs default) + functional indexes; the "all-in-JSON" anti-pattern | Postgres |
| 29-db-hybrid-relational-document | 8229 | Spine of columns + JSONB leaves; `lateral jsonb_array_elements` for SQL over docs | Postgres |
| 30-db-multi-tenancy | 8230 | Shared schema + RLS, schema-per-tenant, DB-per-tenant; how to choose | Postgres |

> Why Postgres for most diagnostic POCs? `EXPLAIN ANALYZE`, `pg_stat_statements`, and `auto_explain` give the clearest visibility into the optimizer's actual decisions. The Oracle equivalents (`dbms_xplan`, `V$SQL`, AWR) are demonstrated in the modules that benefit from them (02, 03, 08). Every JPA-level lesson is DB-agnostic.

---

## Infrastructure

```bash
docker compose --profile postgres up -d           # Postgres only
docker compose --profile oracle up -d             # Oracle 23ai Free only
docker compose --profile core up -d               # Postgres + Oracle + Redis
docker compose --profile redis up -d              # Redis (for m25 caching)
docker compose --profile citus up -d              # Citus coordinator + 2 workers (for m24)
docker compose --profile observability up -d      # + Prometheus + Grafana + pg_exporter
```

| Service | URL |
|---|---|
| Postgres | `jdbc:postgresql://localhost:5432/appdb` (`appuser` / `AppUser123`) |
| Oracle | `jdbc:oracle:thin:@localhost:1521/FREEPDB1` (`appuser` / `AppUser123`) |
| Redis | `redis://localhost:6379` (no auth) |
| Citus coordinator | `jdbc:postgresql://localhost:5433/appdb` (`appuser` / `AppUser123`) |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (anonymous Admin) |

> First Oracle start takes ~3 minutes while it initialises archive logs. Wait for `db-poc-oracle` to report healthy before running modules that target Oracle.

---

## The lessons each module proves

### 01. Indexing — the highest-leverage skill in backend engineering

Demonstrates B-tree vs covering vs partial vs functional vs GIN indexes against a 1M-row table, showing the actual `EXPLAIN ANALYZE` output and the **measured** difference each index makes.

```bash
curl 'http://localhost:8201/seed?rows=1000000'
curl 'http://localhost:8201/bench/btree'
curl 'http://localhost:8201/bench/covering'
curl 'http://localhost:8201/bench/partial'
curl 'http://localhost:8201/bench/functional'
curl 'http://localhost:8201/bench/gin-trigram'
```

### 02. Query plans — read what the optimizer is actually doing

Same query, three plan shapes (Seq Scan, Index Scan, Bitmap Heap Scan). When the optimizer picks each, and how to influence it. Oracle `DBMS_XPLAN.DISPLAY_PLAN` side-by-side with Postgres `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`.

### 03. Slow query hunting — the daily workflow

A built-in `/topN` endpoint that pulls the worst queries from `pg_stat_statements` (and the equivalent `V$SQL` query for Oracle). Reset, run a workload, find what hurt.

### 04. Cardinality estimation — why "good" queries go bad

Load 1M rows, run a query, observe a good plan. Then update correlated columns so statistics lie, re-run the same query, watch the plan collapse. Fix with `ANALYZE`, then with extended statistics (`CREATE STATISTICS ... (dependencies)`).

### 05. N+1 — the JPA bug that's in every codebase

One endpoint per fix variant, all hitting the same data:

| Variant | Approach | SQL count |
|---|---|---|
| `/naive` | `findAll()` then iterate `order.getItems()` lazily | 1 + N |
| `/join-fetch` | JPQL `LEFT JOIN FETCH` | 1 |
| `/entity-graph` | `@EntityGraph(attributePaths = "items")` | 1 |
| `/batch-size` | `@BatchSize(size=20)` | 1 + ⌈N/20⌉ |
| `/dto-projection` | Constructor expression / Tuple → DTO | 1 |
| `/second-level-cache` | `@Cache(...)` on Item | varies |

All variants log the actual SQL count via `SqlCounter` so the difference is undeniable.

### 06. Fetch strategies — why "make it EAGER" is wrong

`LazyInitializationException` demo, OSIV trade-offs, EAGER-by-default-cascading-fetch traps, DTO projection as the actual fix.

### 07. Flush & cascade — "why did saving X delete Y?"

`cascade = ALL` + orphan removal disasters. Flush mode AUTO vs COMMIT timings. Dirty-checking cost on big graphs. The fix: explicit cascades, never `ALL`.

### 08. Batch insert/update — 50–100× faster bulk loads

Side-by-side: `saveAll()` without configuration (slow, no batching) vs with `hibernate.jdbc.batch_size=50` + `order_inserts=true` vs `IDENTITY` PK killing batching vs Oracle sequence with `allocationSize`. JDBC `addBatch` baseline as the floor.

### 09. QueryDSL vs JPQL vs Criteria vs native SQL

One non-trivial dynamic query (filters + paging + projection + join), implemented four ways. Compare LoC, type-safety, refactor pain, generated SQL.

### 10. Isolation levels — fintech's hidden minefield

Concurrent transactions in two threads, every anomaly reproduced and labelled:

- Dirty read (where the DB allows it)
- Non-repeatable read
- Phantom read
- Lost update (read-modify-write race)

Across `READ_COMMITTED`, `REPEATABLE_READ`, `SERIALIZABLE` with the actual behavior on Postgres (REPEATABLE_READ → snapshot, SERIALIZABLE → SSI). Then the fixes: `SELECT ... FOR UPDATE`, optimistic `@Version`, application-level retry.

### 11. Locking — pessimistic done right

Row, table, and intention locks. `SELECT ... FOR UPDATE` vs `FOR UPDATE SKIP LOCKED` vs `FOR UPDATE NOWAIT`. The headline pattern: a job-queue worker using `SKIP LOCKED` so N workers can dequeue from one table without ever blocking each other.

### 12. Deadlock — reproduce, read, fix

Two transactions lock rows in opposite order → deadlock. Postgres detects it, kills the loser with `40P01`. The endpoint dumps the deadlock graph from `pg_locks`. Fix: lock ordering by ID, plus retry on `40P01` at the boundary.

### 13. Optimistic vs pessimistic — throughput under contention

Same write workload through `@Version` and `SELECT FOR UPDATE`. Benchmarked at low / medium / high contention. The hot-row crossover is real: pessimistic wins on contended rows, optimistic wins on dispersed writes.

### 14. Long transactions — the silent prod killer

One transaction held open for 60 seconds. Show: locks not released, MVCC bloat (`pg_stat_user_tables.n_dead_tup`), `xmin` horizon advance stalled, replication lag building, undo segment growth on Oracle. The fix is structural: never do I/O inside a transaction.

### 15. Connection pool — sizing math, not vibes

HikariCP knobs that matter: `maximumPoolSize`, `minimumIdle`, `connectionTimeout`, `maxLifetime`, `idleTimeout`. The sizing math (`pool = ((core_count × 2) + effective_spindle_count)` as a starting point, then measured). What `maxLifetime` actually protects against (firewall idle kills, PgBouncer churn).

### 16. Connection leak — find it before it finds you

A controller that "forgets" to close a manually-acquired connection. With `leakDetectionThreshold=2000` Hikari logs the offending stack trace. Demonstrates the recovery path and the JMX metrics that surface leaks before exhaustion.

### 17. Pool exhaustion — cascading failure mode

Simulated downstream slowness fills the pool, every incoming request times out at `connectionTimeout`, retries make it worse. Then the fix: per-tenant or per-feature Hikari pools (bulkhead) so one slow tenant can't take everyone down.

### 18. Zero-downtime migration — expand / contract

Rename a column without breaking deploys. Expand (add new column, dual-write, backfill), migrate readers, contract (drop old column). One endpoint per phase so you can see the app survive each step.

### 19. Online DDL — index without an outage

`CREATE INDEX CONCURRENTLY` (Postgres), `pg_repack` for table rewrites, Oracle `DBMS_REDEFINITION` and `gh-ost`-style approaches. What "online" actually means (and where it lies).

### 20. Migration rollback — always-forward beats rollback scripts

Why `flyway migrate` is one-way in practice. The "always-forward" pattern: every change is a new migration, never edited. Versioned vs repeatable scripts. Liquibase rollback blocks: when they help and when they're a trap.

### 21. Read replicas — routing reads off the primary

Two HikariCP pools (primary + replica), an `AbstractRoutingDataSource` keyed off `@Transactional(readOnly = true)`, and a deliberate "I read my own write off the replica and got stale data" demo. The read-your-writes fix: route THAT read at the primary, or wait for the LSN to catch up.

### 22. Table partitioning — RANGE / LIST / HASH

Native Postgres declarative partitioning against the same `events` domain in three shapes: monthly RANGE (with sliding-window retention via `DETACH PARTITION` + `DROP TABLE`), LIST by region for routing, HASH by user_id for write spread. `EXPLAIN` shows partition pruning so only the partitions that match the predicate get scanned.

### 23. Sharding — application-level routing

Four HikariCP pools, one per shard schema. A `ShardRouter` with two strategies side-by-side: **modulo** (`hash % N`, easy to reason about, ~3/4 keys move on resize) and **consistent hashing** (TreeMap-backed ring with virtual nodes, ~1/N keys move on resize). Endpoints prove the movement fraction. Scatter-gather across shards via a parallel `ExecutorService`.

### 24. Vitess / Citus — distributed Postgres

A real Citus coordinator + 2 workers via docker-compose. Demonstrates **distributed**, **co-located**, and **reference** tables, and the queries that route to one worker (single-tenant) vs every worker (cross-tenant) vs broadcast (reference join). The "when to graduate from app-level sharding" write-up.

### 25. Caching layers — L1, L2, query, distributed

Hibernate L1 cache (per-session identity), Caffeine as in-process L2, Redis as shared L2. The **cache stampede** demo: N concurrent requests on a cold key fire N DB queries; the **single-flight** registry (`ConcurrentMap<K, CompletableFuture<V>>`) collapses them into 1. Each cache layer is measured per-call.

### 26. Materialized views — pre-aggregated reads

Build a `monthly_sales` MV over orders + items + products, time the expensive aggregate vs the MV select, mutate the underlying rows to show staleness, then refresh — both blocking (`REFRESH MATERIALIZED VIEW`) and concurrent (`REFRESH CONCURRENTLY`, which needs a unique index). Bonus: trigger-maintained computed columns as the "always-fresh" alternative.

### 27. CQRS + outbox — atomic write+publish

The textbook CQRS shape: normalized `orders` write model, `outbox_events` table written in the **same transaction** as the order (no dual-write hole), a `@Scheduled` poller draining the outbox with `FOR UPDATE SKIP LOCKED` and idempotent projection into `user_order_summary` (read model). `/rebuild` truncates the read model and replays the outbox — your read model is cattle.

### 28. JSONB — binary JSON, GIN indexes, the schemaless anti-pattern

Same domain in two shapes: `product_normalized` (every field a column) vs `product_doc` (`data jsonb`). Build GIN indexes both ways (`gin(data)` for keys+containment vs `gin(data jsonb_path_ops)` for containment-only, half the size). Functional index on `(data->>'sku')` for the path-query case. The anti-pattern endpoint shows what constraints JSONB lets through that columns wouldn't.

### 29. Hybrid relational + document — "Postgres replaces MongoDB"

Structured spine (columns + FKs + B-trees) on the same row as a JSONB extension column. Spine queries: indexed lookups. Leaf queries: `@>` containment with GIN. Reporting: `LATERAL jsonb_array_elements` materializes the embedded array as relational rows so you can `GROUP BY` and `JOIN` over them — the move pure document stores struggle with.

### 30. Multi-tenancy — RLS, schema-per-tenant, DB-per-tenant

Three strategies on the same domain. Strategy 1 uses Postgres **Row-Level Security** so a forgotten `where tenant_id=?` can't leak — there's a `/shared/breach/{otherTenant}` endpoint proving it. Strategy 2 sets `search_path` per request to a tenant schema. Strategy 3 (DB-per-tenant) is conceptual with a choosing-flowchart for which to pick at which scale.

---

## How each module is structured

```
NN-name/
├── pom.xml
├── README.md                 # The specific lessons, with copy-paste curl
└── src/main/
    ├── java/com/claude/dbpoc/mNN/
    │   ├── Application.java   # @SpringBootApplication
    │   ├── DemoController.java
    │   ├── domain/            # JPA entities or JDBC records
    │   ├── repo/              # Spring Data + QueryDSL where applicable
    │   └── service/           # The patterns being demonstrated
    └── resources/
        ├── application.yml    # Per-module config (port 82NN)
        ├── schema.sql         # DDL (when not Flyway-managed)
        └── data.sql           # Optional seed
```

---

## The 30-module roadmap, grouped

| Group                          | Modules | What it covers                                                                |
|--------------------------------|---------|-------------------------------------------------------------------------------|
| **Query performance**          | 01–04   | Indexing, plans, slow-query hunting, cardinality estimation                  |
| **JPA correctness**            | 05–07   | N+1, fetch strategies, flush/cascade                                          |
| **JPA throughput**             | 08–09   | Batch insert/update, QueryDSL vs JPQL vs Criteria                            |
| **Concurrency & locking**      | 10–13   | Isolation levels, locking primitives, deadlock, optimistic vs pessimistic    |
| **Transaction discipline**     | 14      | Long transactions and what they break                                         |
| **Connection management**      | 15–17   | Pool sizing, leak detection, exhaustion & bulkhead                            |
| **Schema migration**           | 18–20   | Expand/contract, online DDL, always-forward rollback                          |
| **Scaling: replicas & shape**  | 21–24   | Read replicas, partitioning, app-level sharding, distributed Postgres (Citus) |
| **Caching & read paths**       | 25–27   | Caching layers, materialized views, CQRS + outbox                             |
| **JSON & hybrid storage**      | 28–29   | JSONB primitives, hybrid relational+document pattern                          |
| **Multi-tenancy**              | 30      | Shared schema + RLS, schema-per-tenant, DB-per-tenant                         |

Adjacent batches not in this repo yet: time-series specialization, Oracle AWR / bind-peeking deep dives, full ops/observability stack (Grafana dashboards, alerts).

---

## Java vs. "use the native tool"

Some patterns are better demonstrated as **shell scripts driving SQL** than as Java apps. For each of those, the module includes both — the Java app to run as a service, and the `scripts/` folder with the raw SQL workflow a DBA would actually use. Specifically:

- **02 Query Plan** ships `psql` and `sqlplus` scripts alongside the Java endpoint.
- **03 Slow Query Hunting** ships the actual `pg_stat_statements` / `V$SQL` queries — these are the queries an SRE pastes into a console at 3am.
- **04 Cardinality** ships an `ANALYZE` workflow as SQL.

Java owns the JPA-level POCs (05–09) because the bugs *only exist in JPA*; you can't reproduce N+1 from raw SQL.
