# TECHNICAL — the 30 patterns, dissected

For each module: **the hard problem**, **what we're protecting**, **the
solution shape**, **key tech by responsibility**, **how it solves each
sub-problem**, and **the tech debt to acknowledge**. No fix is free — the last
row of each block is the price you pay.

Read [ISSUE.md](ISSUE.md) first for why this matters, and
[CONSISTENCY.md](CONSISTENCY.md) for what changes when you scale to N pods.

Legend for "key tech by responsibility": `[R]` = the thing that does the work,
`[V]` = the thing that makes the result visible/measurable.

---

## Group 1 — Query performance (01–04)

### 01 · Indexing — `:8201` · Postgres

- **Hard problem:** A `WHERE`/`ORDER BY`/`LIKE` on a 1M-row table does a full
  scan and blows the latency budget. Which *kind* of index actually helps is
  non-obvious and query-shaped.
- **Protecting:** Read latency and CPU/IO budget on the hottest access paths.
- **Solution shape:** Build the same query against B-tree, covering, partial,
  functional, and GIN/trigram indexes; measure each.
- **Key tech by responsibility:** B-tree `[R]` equality/range; covering index
  (`INCLUDE`) `[R]` index-only scans; partial index `[R]` hot-subset queries;
  functional index `[R]` `lower(col)`-style predicates; GIN + `pg_trgm` `[R]`
  `LIKE '%x%'`; `EXPLAIN (ANALYZE, BUFFERS)` `[V]`.
- **How it solves each sub-problem:** Covering index removes heap fetches
  (index-only scan). Partial index shrinks the tree to the rows you query.
  Functional index makes a non-sargable predicate sargable. GIN/trigram makes
  a leading-wildcard `LIKE` indexable at all.
- **Tech debt:** Every index is a write tax (each `INSERT`/`UPDATE` maintains
  it) and disk. Over-indexing degrades writes and confuses the planner. Indexes
  need the right column order; the wrong order is dead weight.

### 02 · Query plans — `:8202` · Postgres + Oracle

- **Hard problem:** The same SQL gets a Seq Scan, an Index Scan, or a Bitmap
  Heap Scan depending on data and stats — and you can't tune what you can't
  read.
- **Protecting:** Your ability to diagnose *why* a query is slow, not guess.
- **Solution shape:** One query, three plan shapes, forced and explained; Oracle
  and Postgres side by side.
- **Key tech by responsibility:** `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`
  `[R/V]` Postgres; `DBMS_XPLAN.DISPLAY_PLAN` `[R/V]` Oracle; selectivity-driven
  predicates `[R]` to trigger each shape.
- **How it solves each sub-problem:** Low selectivity → Seq Scan is *correct*.
  High selectivity → Index Scan. Middle → Bitmap. Reading estimated-vs-actual
  rows exposes when the optimizer was lied to (→ module 04).
- **Tech debt:** `EXPLAIN ANALYZE` actually runs the query (side effects, cost).
  Plan reading is a skill, not a button; JSON output is verbose and
  version-specific.

### 03 · Slow-query hunting — `:8203` · Postgres + Oracle

- **Hard problem:** At 03:00 you don't know *which* query is hurting. You need
  the top-N offenders by total time, now.
- **Protecting:** Mean-time-to-diagnose during an incident.
- **Solution shape:** A `/topN` endpoint plus the raw SQL an SRE pastes into a
  console.
- **Key tech by responsibility:** `pg_stat_statements` `[R]` Postgres aggregate
  stats; `V$SQL` `[R]` Oracle; reset → workload → top-N `[V]` workflow.
- **How it solves each sub-problem:** Ranks by `total_exec_time` (the metric
  that matters — a fast query run a million times beats a slow one run twice).
  Reset isolates a specific workload window.
- **Tech debt:** `pg_stat_statements` normalizes queries (loses literals);
  needs the extension pre-loaded. `V$SQL` ages out of the shared pool. Neither
  survives a restart without snapshotting (AWR territory).

### 04 · Cardinality estimation — `:8204` · Postgres

- **Hard problem:** A query with a great plan silently collapses when column
  statistics go stale or columns are correlated — the optimizer's row estimate
  is off by 1000× and it picks a nested loop over a hash join.
- **Protecting:** Plan stability over time; the query that was fast last week
  staying fast this week.
- **Solution shape:** Good plan → corrupt the stats → watch the same query's
  plan collapse → fix with `ANALYZE`, then extended statistics.
- **Key tech by responsibility:** `ANALYZE` `[R]` refresh stats;
  `CREATE STATISTICS … (dependencies)` `[R]` multi-column correlation;
  estimated-vs-actual rows in `EXPLAIN` `[V]`.
- **How it solves each sub-problem:** `ANALYZE` fixes staleness. Extended stats
  fix the *independence assumption* — when `city` and `country` correlate, the
  planner stops multiplying selectivities into a fantasy.
- **Tech debt:** Extended statistics must be created *and maintained* per
  column combination; they don't auto-discover. Autovacuum/analyze tuning is
  workload-specific. This is ongoing curation, not a one-time fix.

---

## Group 2 — JPA correctness (05–07)

### 05 · N+1 and five fixes — `:8205` · Postgres

- **Hard problem:** `findAll()` + lazy `getItems()` fires **1 + N** queries.
  Invisible in review, a P95 killer at scale. The hard part is choosing among
  five fixes that each break something else.
- **Protecting:** Round-trip count — the number that shows up in APM traces and
  decides whether the endpoint survives.
- **Solution shape:** Six endpoints on identical data, each returning
  `sqlStatements`.
- **Key tech by responsibility:** JOIN FETCH `[R]` single-collection; `@EntityGraph`
  `[R]` fixed reusable plan; `@BatchSize` `[R]` IN-clause batching; DTO
  projection `[R]` read paths; L2 cache `[R]` reference data; `SqlCounter`
  (JDBC proxy + `AtomicLong`) `[V]`.
- **How it solves each sub-problem:** JOIN FETCH → 1 query. EntityGraph → 1,
  declaratively. BatchSize → `1 + ⌈N/size⌉`. DTO → 1, and *cannot* re-trigger
  N+1 (no managed entity). L2 → ~1 on warm pass.
- **Tech debt:** JOIN FETCH + `Pageable` on a collection → in-memory pagination
  (`HHH000104`). Two collection JOIN FETCHes → cartesian product /
  `MultipleBagFetchException`. DTO can't be mutated & saved. L2 cold pass = the
  disease; invalidation cost eats write-heavy paths.

### 06 · Fetch strategies — `:8206` · Postgres

- **Hard problem:** "Just make it EAGER" is the wrong fix. EAGER cascades into
  giant unavoidable joins; LAZY throws `LazyInitializationException` outside a
  session; OSIV hides the problem by holding the session open too long.
- **Protecting:** Predictable, bounded fetch cost and clean transaction
  boundaries.
- **Solution shape:** Reproduce LIE, show OSIV trade-off, show EAGER cascade
  trap, land on DTO projection as the real fix.
- **Key tech by responsibility:** `FetchType.LAZY` `[R]` default; `@Transactional`
  boundary `[R]` session lifetime; `spring.jpa.open-in-view` `[R]` the OSIV
  toggle; DTO projection `[R]` the fix; forced LIE endpoint `[V]`.
- **How it solves each sub-problem:** LAZY + explicit fetch = you control the
  graph. Turning OSIV *off* surfaces LIE at dev time instead of hiding
  connection-hold in prod. DTO fetches exactly the columns needed.
- **Tech debt:** LAZY needs discipline (fetch what you'll touch, inside the tx).
  Turning OSIV off breaks lazy access in controllers — a migration cost on
  existing codebases.

### 07 · Flush & cascade — `:8207` · Postgres

- **Hard problem:** "Why did saving X delete Y?" `cascade = ALL` +
  `orphanRemoval` produces surprise deletes; flush-mode timing produces
  surprise writes; dirty-checking a big graph is a hidden CPU cost.
- **Protecting:** Data integrity — nothing gets written or deleted that you
  didn't intend.
- **Solution shape:** Reproduce the cascade delete disaster and the flush-timing
  surprise, then constrain cascades explicitly.
- **Key tech by responsibility:** Explicit `cascade = {PERSIST, MERGE}` `[R]`
  instead of `ALL`; `FlushModeType` AUTO vs COMMIT `[R]` write timing; p6spy
  SQL log `[V]`.
- **How it solves each sub-problem:** Narrow cascades stop orphanRemoval from
  reaching rows you didn't mean. FlushMode COMMIT batches writes to tx end.
  Seeing the SQL exposes the auto-flush before a query.
- **Tech debt:** Explicit cascades are more verbose and easy to under-specify
  (forget PERSIST → `TransientObjectException`). FlushMode COMMIT can make a
  same-tx read miss an un-flushed write.

---

## Group 3 — JPA throughput (08–09)

### 08 · Batch insert/update — `:8208` · Postgres + Oracle

- **Hard problem:** `saveAll()` with default config issues one round-trip per
  row — 50–100× slower than it should be. `IDENTITY` PKs silently disable JDBC
  batching entirely.
- **Protecting:** Bulk-load throughput and the DB's round-trip budget.
- **Solution shape:** `saveAll` unbatched vs batched vs IDENTITY-killed vs
  Oracle sequence, with a raw JDBC `addBatch` floor.
- **Key tech by responsibility:** `hibernate.jdbc.batch_size` `[R]`;
  `order_inserts` / `order_updates` `[R]` group by table for full batches;
  sequence + `allocationSize` `[R]` batch-compatible IDs; JDBC `addBatch()`
  `[V]` baseline.
- **How it solves each sub-problem:** `batch_size` groups statements into one
  round-trip. `order_inserts` prevents batch breaks when entities interleave.
  Sequence allocation avoids the `IDENTITY` post-insert-value fetch that forces
  row-at-a-time.
- **Tech debt:** `IDENTITY` PK vs batching is a schema-level constraint you may
  not control. `allocationSize` gaps IDs (fine, but surprises people). `rewriteBatchedInserts`
  is driver-specific. Batch size interacts with memory and lock hold time.

### 09 · QueryDSL vs JPQL vs Criteria vs native — `:8209` · Postgres

- **Hard problem:** A dynamic query (filters + paging + projection + join)
  written the wrong way is unreadable, unsafe, or un-refactorable.
- **Protecting:** Long-term maintainability and compile-time safety of the query
  layer.
- **Solution shape:** The same non-trivial query, four ways, compared on LoC,
  type-safety, refactor pain, generated SQL.
- **Key tech by responsibility:** QueryDSL Q-types `[R]` type-safe dynamic;
  JPQL `[R]` readable static; Criteria API `[R]` programmatic; native SQL `[R]`
  DB-specific escape hatch; generated-SQL log `[V]`.
- **How it solves each sub-problem:** QueryDSL wins dynamic + refactor-safety
  (renames caught at compile). JPQL wins readability for static queries. Native
  wins when you need a DB feature JPQL can't express.
- **Tech debt:** QueryDSL needs an annotation-processor build step and Q-type
  regeneration. Native SQL forfeits portability and type-safety. Four styles in
  one codebase is itself a consistency cost — pick a default.

---

## Group 4 — Concurrency & locking (10–13)

### 10 · Isolation levels — `:8210` · Postgres

- **Hard problem:** Concurrent transactions produce dirty reads, non-repeatable
  reads, phantoms, and lost updates — and the anomalies you get depend on the
  isolation level, which most people never set deliberately.
- **Protecting:** Correctness of concurrent reads and writes — in fintech, the
  correctness of money.
- **Solution shape:** Two threads, every anomaly reproduced and labelled across
  READ_COMMITTED / REPEATABLE_READ / SERIALIZABLE.
- **Key tech by responsibility:** `@Transactional(isolation = …)` `[R]`;
  Postgres MVCC snapshot `[R]` REPEATABLE_READ; SSI `[R]` SERIALIZABLE;
  two-thread harness `[V]`.
- **How it solves each sub-problem:** REPEATABLE_READ's snapshot kills
  non-repeatable & phantom reads. SERIALIZABLE (SSI) kills the lost-update /
  write-skew that snapshot alone misses — by aborting one transaction.
- **Tech debt:** SERIALIZABLE means **serialization failures you must retry**
  (`40001`). Higher isolation = more aborts = throughput cost. Postgres has no
  dirty read at all, so behavior isn't portable from other DBs.

### 11 · Locking — `:8211` · Postgres

- **Hard problem:** You need to coordinate concurrent workers without them
  blocking each other or double-processing a row.
- **Protecting:** Throughput of contended workloads (job queues, counters).
- **Solution shape:** Row/table/intention locks, and a `SKIP LOCKED` job-queue
  where N workers dequeue from one table with zero mutual blocking.
- **Key tech by responsibility:** `SELECT … FOR UPDATE` `[R]` pessimistic row
  lock; `FOR UPDATE SKIP LOCKED` `[R]` non-blocking dequeue;
  `FOR UPDATE NOWAIT` `[R]` fail-fast; `pg_locks` `[V]`.
- **How it solves each sub-problem:** `SKIP LOCKED` steps over already-locked
  rows so each worker grabs a disjoint batch. `NOWAIT` turns a wait into an
  immediate error for latency-sensitive paths.
- **Tech debt:** `SKIP LOCKED` gives up FIFO ordering. Held locks serialize
  writers — the queue table is a contention point at very high throughput
  (eventually → a real broker). Lock scope creep causes the deadlocks of m12.

### 12 · Deadlock — `:8212` · Postgres

- **Hard problem:** Two transactions lock rows in opposite order → deadlock;
  Postgres kills one with `40P01`. Reproducing and *reading* it is the skill.
- **Protecting:** Availability of write paths under contention.
- **Solution shape:** Force the deadlock, dump the graph from `pg_locks`, fix
  with lock ordering + boundary retry.
- **Key tech by responsibility:** Deterministic **lock ordering by ID** `[R]`
  prevention; retry-on-`40P01` at the tx boundary `[R]` recovery; `pg_locks` /
  deadlock graph `[V]`.
- **How it solves each sub-problem:** Ordering makes a cycle impossible (no two
  txns can hold-and-wait in opposite directions). Retry absorbs the residual
  loser cleanly.
- **Tech debt:** Global lock ordering is a discipline every writer must follow —
  one non-conforming code path reintroduces the cycle. Retries must be
  idempotent and bounded, or they amplify (see m17).

### 13 · Optimistic vs pessimistic — `:8213` · Postgres

- **Hard problem:** Under contention, `@Version` (optimistic) and
  `SELECT FOR UPDATE` (pessimistic) have opposite throughput profiles, and a
  single hot row punishes the wrong choice hard.
- **Protecting:** Write throughput *and* correctness on contended rows.
- **Solution shape:** Same workload through both, benchmarked at low/medium/high
  contention to find the crossover.
- **Key tech by responsibility:** `@Version` + retry `[R]` optimistic;
  `PESSIMISTIC_WRITE` / `FOR UPDATE` `[R]` pessimistic; contention-level
  benchmark `[V]`.
- **How it solves each sub-problem:** Optimistic wins dispersed writes (no lock,
  rare conflict). Pessimistic wins a hot row (serialize once vs retry-storm the
  version check).
- **Tech debt:** Optimistic requires app-level retry logic and idempotent
  writes; degenerates to a livelock on a hot row. Pessimistic holds locks (→
  m14 long-tx risk) and can deadlock (→ m12). Neither is a default; it's a
  per-access-pattern decision.

---

## Group 5 — Transaction discipline (14)

### 14 · Long transactions — `:8214` · Postgres

- **Hard problem:** One transaction held open (often by doing network I/O inside
  it) holds locks, bloats MVCC, stalls the `xmin` horizon, builds replication
  lag, and grows undo — degrading the *whole* DB, not just itself.
- **Protecting:** The health of the entire database instance.
- **Solution shape:** Hold a tx for 60s and watch each symptom appear.
- **Key tech by responsibility:** short-tx discipline / **no I/O in a tx** `[R]`;
  `pg_stat_user_tables.n_dead_tup`, `xmin` horizon, replication lag `[V]`.
- **How it solves each sub-problem:** Closing the tx before doing I/O releases
  locks, lets autovacuum reclaim dead tuples, and advances the horizon so
  vacuum can actually clean up.
- **Tech debt:** "No I/O in a transaction" forces you to restructure code
  (fetch → close tx → call service → open tx → write). Saga/compensation
  patterns replace the convenient-but-fatal long tx, at real design cost.

---

## Group 6 — Connection management (15–17)

### 15 · Connection pool — `:8215` · Postgres

- **Hard problem:** Pool size set by vibes: too small starves legitimate
  concurrency, too large overloads the DB and makes latency *worse*.
- **Protecting:** The service's concurrency ceiling and the DB's memory budget.
- **Solution shape:** Tune each HikariCP knob and show the sizing math, then
  measure.
- **Key tech by responsibility:** `maximumPoolSize` / `minimumIdle` `[R]`
  concurrency; `connectionTimeout` `[R]` fail-fast; `maxLifetime` `[R]`
  firewall/PgBouncer idle-kill protection; `idleTimeout` `[R]`; Hikari metrics
  `[V]`.
- **How it solves each sub-problem:** Sizing starts at `((cores × 2) + spindles)`
  then is *measured*. `maxLifetime` retires connections before an external
  actor kills them under you.
- **Tech debt:** The "right" size is workload- and infra-specific — it must be
  re-derived after any change, and **it multiplies by pod count** (see
  CONSISTENCY.md). A number that's correct at 1 replica exhausts the DB at 20.

### 16 · Connection leak — `:8216` · Postgres

- **Hard problem:** A path that acquires a connection and forgets to close it
  slowly drains the pool until nothing works — and the stack trace of the
  culprit is long gone by the time you notice.
- **Protecting:** Pool availability over time; catching the bug before
  exhaustion.
- **Solution shape:** A deliberately-leaking endpoint + Hikari leak detection
  logging the offending stack.
- **Key tech by responsibility:** `leakDetectionThreshold` `[R]` catch + log;
  try-with-resources / framework-managed connections `[R]` prevention; JMX pool
  metrics `[V]`.
- **How it solves each sub-problem:** Leak detection prints the acquiring stack
  after N ms so you find the exact line. Framework-managed connections make the
  leak structurally impossible.
- **Tech debt:** `leakDetectionThreshold` only *reports* — it doesn't reclaim
  (until `maxLifetime`). Set too low it false-positives on legitimately slow
  work. It's a smoke detector, not a fix.

### 17 · Pool exhaustion & bulkhead — `:8217` · Postgres

- **Hard problem:** A single shared pool is a single shared budget — one slow
  path holds connections, everything else queues and times out, and naive
  retries make it strictly worse.
- **Protecting:** Blast-radius containment — one slow feature must not take down
  the service.
- **Solution shape:** Cascade → retry-storm (amplification ≈ 3×) → bulkhead
  (separate pool/semaphore per risky path/tenant).
- **Key tech by responsibility:** separate `HikariDataSource` per feature `[R]`
  strict bulkhead; `Semaphore` per feature/tenant `[R]` lightweight bulkhead;
  timed cascade/retry/bulkhead endpoints `[V]`.
- **How it solves each sub-problem:** A dedicated pool means the slow path
  starves *only itself*. Per-tenant semaphores stop a loud customer starving
  quiet ones.
- **Tech debt:** More pools = more total connections against the DB (interacts
  with m15 sizing and pod count). **A per-pod semaphore is not a global limit**
  — N pods × permit-count is the real concurrency the DB sees (see
  CONSISTENCY.md). Bulkhead partitioning is a capacity-planning burden.

---

## Group 7 — Schema migration (18–20)

### 18 · Zero-downtime migration — `:8218` · Postgres

- **Hard problem:** Renaming/altering a column while old and new app versions
  run simultaneously (rolling deploy) breaks whichever version doesn't match
  the schema.
- **Protecting:** Deploy availability — no outage window for a schema change.
- **Solution shape:** Expand/contract: add new column → dual-write → backfill →
  migrate readers → contract (drop old). One endpoint per phase.
- **Key tech by responsibility:** additive DDL `[R]` expand; dual-write/dual-read
  application code `[R]` transition; backfill job `[R]`; per-phase endpoints
  `[V]`.
- **How it solves each sub-problem:** Every intermediate state is compatible
  with *both* app versions, so a rolling deploy never sees a schema it can't
  handle.
- **Tech debt:** Multi-deploy choreography (expand and contract are *separate
  releases*, often days apart). Dual-write code is temporary cruft that must be
  cleaned up — and often isn't. Backfill on a huge table needs batching (→ m19).

### 19 · Online DDL — `:8219` · Postgres + Oracle

- **Hard problem:** A plain `CREATE INDEX` or `ALTER TABLE` takes a lock that
  blocks writes for the duration — an outage on a big table.
- **Protecting:** Write availability during schema changes on large tables.
- **Solution shape:** `CREATE INDEX CONCURRENTLY`, `pg_repack`, Oracle
  `DBMS_REDEFINITION` / `gh-ost`-style, and where "online" actually lies.
- **Key tech by responsibility:** `CREATE INDEX CONCURRENTLY` `[R]` no
  write-blocking; `pg_repack` `[R]` online table rewrite; `DBMS_REDEFINITION`
  `[R]` Oracle; lock inspection `[V]`.
- **How it solves each sub-problem:** CONCURRENTLY builds the index in two
  passes without an exclusive lock. `pg_repack` rewrites via triggers +
  swap instead of `VACUUM FULL`'s exclusive lock.
- **Tech debt:** CONCURRENTLY is slower, can't run in a transaction, and leaves
  an **invalid index** if it fails (manual cleanup). `pg_repack` needs a PK,
  disk headroom (2×), and an extension. "Online" still spikes IO/WAL.

### 20 · Migration rollback — `:8220` · Postgres

- **Hard problem:** People expect to "roll back" a migration, but in practice
  `migrate` is one-way — a down-script on data-bearing changes loses data or
  doesn't fit the current state.
- **Protecting:** Schema-history integrity and safe recovery from a bad change.
- **Solution shape:** The **always-forward** pattern — every change is a new
  migration, never edited; versioned vs repeatable; where Liquibase rollback
  helps vs traps.
- **Key tech by responsibility:** Flyway/Liquibase versioned migrations `[R]`;
  repeatable migrations `[R]` for views/procs; forward-fix migration `[R]`
  instead of rollback; checksum/history table `[V]`.
- **How it solves each sub-problem:** Always-forward means recovery is *another
  migration*, which is testable and deployable like any other, instead of a
  hand-run down-script against prod.
- **Tech debt:** Discipline-based (nothing enforces "never edit an applied
  migration" but a checksum error). A truly bad expand step still needs a
  compensating forward migration designed under pressure. Rollback blocks lull
  you into thinking undo is safe when it usually isn't.

---

## Group 8 — Scaling the data (21–24)

### 21 · Read replica — `:8221` · Postgres

- **Hard problem:** Reads overwhelm the primary; routing them to a replica
  introduces **replication lag** → "I read my own write and got stale data".
- **Protecting:** Primary write capacity, without lying to the user about their
  own writes.
- **Solution shape:** Two pools + `AbstractRoutingDataSource` keyed off
  `readOnly`, plus a deliberate stale-read demo and its fix.
- **Key tech by responsibility:** `AbstractRoutingDataSource` `[R]` route;
  `@Transactional(readOnly = true)` `[R]` the routing key; read-your-writes fix
  (route THAT read at primary / wait for LSN) `[R]`; lag demo `[V]`.
- **How it solves each sub-problem:** Read-only tx → replica pool → primary
  offloaded. For the user's own just-written data, route that specific read at
  the primary (or gate on replay LSN).
- **Tech debt:** Every read path must be correctly annotated `readOnly` or it
  silently hits the primary (or worse, a stale replica). Read-your-writes logic
  leaks routing awareness into the domain. Replica failover is its own project.

### 22 · Table partitioning — `:8222` · Postgres

- **Hard problem:** One giant table is slow to query and impossible to prune
  (deleting old rows = a massive `DELETE`). Vacuum and index bloat scale with
  table size.
- **Protecting:** Query cost on time-ranged data and cheap retention.
- **Solution shape:** RANGE (monthly, sliding-window retention), LIST (region),
  HASH (user_id write-spread); `EXPLAIN` proves pruning.
- **Key tech by responsibility:** declarative partitioning `[R]`;
  `DETACH PARTITION` + `DROP TABLE` `[R]` O(1) retention; partition pruning
  `[R]` scan only matching partitions; `EXPLAIN` partition list `[V]`.
- **How it solves each sub-problem:** Pruning scans only the months the
  predicate touches. Retention is a metadata `DETACH` + `DROP`, not a row-by-row
  `DELETE`.
- **Tech debt:** The partition key must be in the query predicate or pruning
  doesn't happen (and you scan everything). Unique constraints must include the
  key. Partition maintenance (creating next month's partition) needs automation.
  Cross-partition queries fan out.

### 23 · Sharding — `:8223` · Postgres

- **Hard problem:** One DB node can't hold the write volume. Splitting across
  nodes means choosing a routing scheme whose *resharding cost* differs by 3×.
- **Protecting:** Horizontal write capacity, with bounded key-movement on
  resize.
- **Solution shape:** Four pools/schemas; `ShardRouter` with modulo vs
  consistent hashing; endpoints prove the movement fraction; parallel
  scatter-gather.
- **Key tech by responsibility:** modulo `hash % N` `[R]` simple (≈3/4 keys move
  on resize); consistent hashing (TreeMap ring + virtual nodes) `[R]` (≈1/N
  move); `ExecutorService` scatter-gather `[R]` cross-shard; movement-fraction
  endpoint `[V]`.
- **How it solves each sub-problem:** Consistent hashing localizes remapping to
  one node's slice on resize. Scatter-gather parallelizes the unavoidable
  cross-shard query.
- **Tech debt:** No cross-shard transactions or FKs; joins become app-side
  scatter-gather. The shard key is a near-permanent decision. Rebalancing is
  operationally heavy even at 1/N. Config (ring, virtual-node count) must be
  **identical on every pod** (see CONSISTENCY.md) or keys route differently.

### 24 · Vitess / Citus — `:8224` · Postgres (Citus)

- **Hard problem:** App-level sharding (m23) pushes routing complexity into your
  code; at some scale you want the database to own distribution.
- **Protecting:** Distribution logic — moving it out of the app into the DB
  layer.
- **Solution shape:** Real Citus coordinator + 2 workers; distributed,
  co-located, and reference tables; queries routed to one/all/broadcast.
- **Key tech by responsibility:** distributed table (`create_distributed_table`)
  `[R]` sharded; co-located tables `[R]` local joins; reference table `[R]`
  broadcast/replicated; coordinator routing `[V]`.
- **How it solves each sub-problem:** Single-tenant queries route to one worker.
  Co-location keeps a tenant's joinable tables on the same worker (local join).
  Reference tables replicate small dimension data everywhere so joins stay
  local.
- **Tech debt:** Operational weight of a distributed cluster (coordinator is a
  SPOF/bottleneck; workers to manage). Distribution column choice is as
  permanent as a shard key. Not every query distributes well; some still fan out
  to all workers. "When to graduate" is a real judgement call.

---

## Group 9 — Read-path scaling (25–27)

### 25 · Caching layers — `:8225` · Postgres + Redis

- **Hard problem:** Repeated identical reads hammer the DB; and on a cold key, N
  concurrent requests all miss and fire N queries (**cache stampede**).
- **Protecting:** DB read load and tail latency on hot keys.
- **Solution shape:** Hibernate L1, Caffeine (in-proc L2), Redis (shared L2);
  stampede demo collapsed by a single-flight registry.
- **Key tech by responsibility:** Hibernate L1 `[R]` per-session identity;
  Caffeine `[R]` in-process L2; Redis `[R]` shared L2 across instances;
  single-flight (`ConcurrentMap<K, CompletableFuture<V>>`) `[R]` stampede
  collapse; per-call timing `[V]`.
- **How it solves each sub-problem:** Layered caches serve progressively cheaper
  hits. Single-flight lets the first request compute while the rest await the
  same future — N misses become 1 query.
- **Tech debt:** **In-process caches diverge across pods** (Caffeine on pod A
  ≠ pod B) → need Redis or invalidation pub/sub (see CONSISTENCY.md).
  Invalidation is the hard problem — every cache adds a staleness window. Redis
  is another dependency that can fail.

### 26 · Materialized view — `:8226` · Postgres

- **Hard problem:** An expensive aggregate recomputed on every read is wasteful;
  but a pre-computed view goes stale the moment the base rows change.
- **Protecting:** Read latency on aggregate/report queries.
- **Solution shape:** Build `monthly_sales` MV, time it vs the live aggregate,
  mutate base rows to show staleness, refresh blocking vs concurrent; plus
  trigger-maintained columns as the always-fresh alternative.
- **Key tech by responsibility:** `MATERIALIZED VIEW` `[R]` precompute;
  `REFRESH … CONCURRENTLY` (+ unique index) `[R]` refresh without read-blocking;
  trigger-maintained computed column `[R]` always-fresh; timing `[V]`.
- **How it solves each sub-problem:** The MV turns an aggregate scan into a
  single-row select. CONCURRENTLY refreshes without blocking readers. Triggers
  keep a derived value correct on every write (no staleness, at write cost).
- **Tech debt:** MV staleness window = refresh interval; who triggers refresh?
  CONCURRENTLY needs a unique index and is slower. Triggers move cost to the
  write path and can become a hidden hotspot. Refresh on a huge MV is itself
  expensive.

### 27 · CQRS + outbox — `:8227` · Postgres

- **Hard problem:** Splitting write and read models is easy; keeping them in
  sync **without losing writes** is not. Naive "save then publish" is a dual
  write that drops events when the publish fails.
- **Protecting:** No lost events between write and read models; a rebuildable
  read side.
- **Solution shape:** Write + `outbox_events` in **one transaction**; a
  `SKIP LOCKED` poller projects idempotently into the read model; `/rebuild`
  replays.
- **Key tech by responsibility:** transactional outbox `[R]` atomic
  write+publish; `FOR UPDATE SKIP LOCKED` poller `[R]` ordered, multi-instance
  drain; idempotency via `last_event_id` `[R]` at-least-once safety; `/rebuild`
  `[R]` disposable read model; outbox backlog metric `[V]`.
- **How it solves each sub-problem:** Same-tx insert removes the dual-write hole.
  `ORDER BY id` + SKIP LOCKED drains in commit order across pollers.
  `last_event_id` makes replays no-ops → the read model is cattle.
- **Tech debt:** At-least-once, **not** exactly-once (two-generals) — every
  projection *must* be idempotent. Outbox table grows and needs retention (→
  m22 partitioning). Poller is a moving part to health-check; with multiple pods
  it needs SKIP LOCKED (it has it) or leader election (see CONSISTENCY.md).
  Eventual consistency is user-visible.

---

## Group 10 — Storage shape & tenancy (28–30)

### 28 · JSONB — `:8228` · Postgres

- **Hard problem:** "Just put it all in JSON" trades schema safety for
  flexibility — and then queries and indexes over JSON are their own puzzle
  (which GIN operator class? how to index a path?).
- **Protecting:** Query performance and *some* integrity over semi-structured
  data.
- **Solution shape:** Normalized columns vs `data jsonb`; `gin(data)` vs
  `gin(data jsonb_path_ops)`; functional index on a path; the anti-pattern
  endpoint.
- **Key tech by responsibility:** `jsonb` `[R]` binary JSON; `gin(data)` `[R]`
  keys + containment; `gin(data jsonb_path_ops)` `[R]` containment-only (half
  the size); functional index on `(data->>'sku')` `[R]` path equality; constraint
  comparison `[V]`.
- **How it solves each sub-problem:** `jsonb_path_ops` shrinks the index when you
  only do `@>`. A functional index makes a specific JSON path as fast as a
  column. The anti-pattern endpoint shows what JSONB lets through that a column
  wouldn't (types, NOT NULL, FKs).
- **Tech debt:** JSONB has **no schema, no type enforcement, no FKs** — integrity
  moves to the app. GIN indexes are large and write-costly. Deep-path queries
  are verbose. "Schemaless" is a liability the day you need a constraint.

### 29 · Hybrid relational + document — `:8229` · Postgres

- **Hard problem:** Pure relational is rigid for evolving attributes; pure
  document loses joins/aggregation. You want both on the same row.
- **Protecting:** Both indexed integrity (spine) and flexible extension (leaf)
  without a second datastore.
- **Solution shape:** Structured column spine (FKs + B-trees) + a `jsonb`
  extension column; spine queries indexed, leaf queries `@>` on GIN, reporting
  via `LATERAL jsonb_array_elements`.
- **Key tech by responsibility:** column spine + B-tree/FK `[R]` integrity &
  indexed lookups; `jsonb` leaf + GIN `[R]` flexible attributes;
  `LATERAL jsonb_array_elements` `[R]` relationalize embedded arrays for
  `GROUP BY`/`JOIN`; comparison `[V]`.
- **How it solves each sub-problem:** The spine keeps referential integrity and
  fast lookups; the leaf absorbs schema churn; LATERAL lets SQL aggregate over
  embedded arrays — the thing pure document stores struggle with.
- **Tech debt:** Two mental models on one table; discipline about what belongs in
  the spine vs the leaf. LATERAL over big arrays is expensive. The flexibility of
  the leaf is also its lack of guarantees (m28's debt applies to the leaf).

### 30 · Multi-tenancy — `:8230` · Postgres

- **Hard problem:** Serving many tenants from one system: a forgotten
  `WHERE tenant_id = ?` **leaks another tenant's data** — a security incident,
  not a bug.
- **Protecting:** Tenant isolation (data confidentiality) and cost/scale
  trade-off.
- **Solution shape:** Three strategies on one domain — shared schema + RLS,
  schema-per-tenant (`search_path`), DB-per-tenant — with a breach endpoint
  proving RLS holds and a choosing flowchart.
- **Key tech by responsibility:** Postgres **Row-Level Security** `[R]`
  DB-enforced isolation; per-request `search_path` `[R]` schema-per-tenant;
  DB-per-tenant `[R]` hard isolation; `/shared/breach/{otherTenant}` `[V]`
  proves RLS blocks the leak.
- **How it solves each sub-problem:** RLS enforces the tenant predicate in the
  *database*, so a forgotten `WHERE` can't leak. `search_path` gives stronger
  isolation with shared infra. DB-per-tenant gives maximum isolation for
  regulated/large tenants.
- **Tech debt:** RLS is easy to misconfigure and adds a per-query policy check
  (planner cost). Schema-per-tenant explodes object count and migration fan-out
  (run every migration × N schemas). DB-per-tenant multiplies operational and
  connection-pool cost. **The tenant context must be set per request on every
  pod** (see CONSISTENCY.md) or isolation silently fails.

---

## The cross-cutting tech debt

Reading top to bottom, five debts recur in almost every module:

1. **Every fix is a discipline, not a guardrail.** Lock ordering, `readOnly`
   annotations, narrow cascades, always-forward migrations — nothing enforces
   them but review. One non-conforming path reintroduces the failure.
2. **Every cache/precompute buys latency with staleness.** L2, MV, CQRS read
   model, replicas — all introduce a window where the answer is old.
3. **Every "just add more" hides the real bottleneck.** Bigger pool, more
   replicas, more shards — each delays a failure instead of fixing it, and
   moves the bottleneck downstream.
4. **Idempotency and retry are load-bearing.** Optimistic writes, deadlock
   retry, at-least-once outbox — all *require* idempotent, bounded retries or
   they amplify into outages (m17).
5. **Almost none of it is pod-count-neutral.** Pool sizes, in-process caches,
   schedulers, semaphores, shard config — the correct single-instance answer is
   frequently *wrong* at N replicas. That is the entire subject of
   [CONSISTENCY.md](CONSISTENCY.md).
