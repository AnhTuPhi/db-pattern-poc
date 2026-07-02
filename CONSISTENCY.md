# CONSISTENCY — what horizontal scaling does to every pattern

> You tuned all 30 modules on **one instance**. Then you set
> `replicas: 20` (or added VMs behind a load balancer). Roughly a third of the
> patterns just silently changed behavior — some correctly, some catastrophically.

This document is the bridge between "works on my one pod" and "works on the
fleet". It answers one question per pattern: **when I go from 1 instance to N,
does this still hold — and what do I have to change?**

The two scaling models it covers:

- **k8s (pods):** many identical, *ephemeral* replicas behind a Service. HPA
  can add/remove them at any moment. Rolling deploys mean **two app versions run
  at once**. Pods restart, get rescheduled, lose in-memory state.
- **VM fleet:** coarser, longer-lived instances behind a load balancer. Same
  core issues (shared DB, divergent local state) but slower churn, no rolling
  schema surprises unless you deploy that way, and manual scaling.

The database underneath is (usually) still **one logical primary**. That's the
crux: **N stateless app instances share one stateful database.** Every
consistency problem below is a variant of that mismatch.

---

## The master rule

> **State that lives inside a pod is per-pod. State that lives in the database
> is shared. Correctness at scale means putting shared truth in the DB (or
> Redis), and never in a pod's heap.**

Anything you cached, counted, locked, or scheduled *in Java memory* now exists
in N independent copies that don't know about each other.

---

## Scaling impact by pattern

Severity: 🟢 unaffected / already-correct · 🟡 needs configuration or care ·
🔴 silently breaks — must be re-engineered.

### Connection pools (m15, m16, m17) — 🔴 the #1 scaling trap

**What breaks:** Pool size is **per pod**. Your carefully-measured
`maximumPoolSize: 20` becomes `20 × podCount` connections against the DB.

```
1 pod  × 20 =   20 connections   ✅ (DB max_connections = 200)
20 pods × 20 =  400 connections   ❌ exceeds max_connections → "too many clients"
```

HPA makes this worse: a traffic spike scales pods *up*, each opening a full
pool, and the **connection storm** can knock the DB over exactly when you need
it most.

**What to do:**
- Size pools as `dbConnectionBudget / maxPods`, not per-pod in isolation.
  Leave headroom for migrations, admin, replicas.
- Put **PgBouncer** (transaction pooling) between the fleet and Postgres so the
  DB sees a bounded connection count regardless of pod count. This is close to
  mandatory past ~10 pods.
- Cap HPA `maxReplicas` with the connection budget in mind.
- The **bulkhead semaphore (m17) is per-pod** — a `Semaphore(4)` on 20 pods
  allows 80 concurrent slow queries to the DB, not 4. A per-pod bulkhead limits
  *that pod's* blast radius but is **not a global concurrency limit**. For a
  true global cap you need a DB-side `statement_timeout` + a separate DB
  role/pool, or a distributed limiter.

**VM note:** identical math — pool × VM count. VMs churn slower so no connection
storm, but the ceiling is the same.

### In-process caches (m25 Caffeine, Hibernate L1/L2) — 🔴 divergence

**What breaks:** Caffeine and any in-heap L2 live **in one pod's memory**. Pod A
caches `user:7 = balance 100`. An update hits pod B, which invalidates *its*
copy. Pod A still serves 100. Two users, same query, **different answers** — and
it never self-heals until TTL.

The **single-flight** stampede fix (m25) also only collapses requests *within
one pod*. N pods on a cold key still fire N queries (one per pod), not N per
pod — better, but not 1.

**What to do:**
- Use **Redis as the shared L2** (m25 already demonstrates this) for anything
  that must be coherent across pods. In-process Caffeine is fine only for
  *immutable* reference data or data where a per-pod-TTL staleness window is
  acceptable.
- For invalidation, publish invalidation events (Redis pub/sub, or the CDC/
  outbox stream) so every pod drops its local copy.
- Hibernate **L1 is per-session** — unaffected by scaling (it dies with the
  request). Hibernate **L2**, if in-process, has the same divergence problem as
  Caffeine; back it with Redis/Infinispan for a clustered L2.

### Scheduled jobs & pollers (m26 refresh, m27 outbox poller) — 🟡/🟢

**What breaks:** `@Scheduled` runs **on every pod**. Twenty pods = twenty
pollers, twenty MV refreshes firing at once — duplicate work, and for a
non-idempotent job, wrong results.

**Why m27 is already safe:** the outbox poller uses
`FOR UPDATE SKIP LOCKED`. Every pod's poller grabs a *disjoint* batch; they
cooperate instead of colliding. This is the **correct pattern** for
multi-instance scheduled work — the scheduler doesn't need to be a singleton
because the *database* arbitrates. 🟢

**Where you still need care (m26 MV refresh):** `REFRESH MATERIALIZED VIEW` from
20 pods at once is 20× the load and can deadlock/serialize. Make it a singleton:
- **Leader election / distributed lock** — `ShedLock` (a DB/Redis lock table) so
  exactly one pod runs the job per interval; or a k8s `Lease`; or run refresh as
  a separate **`CronJob`** (one pod, on schedule) rather than inside the API
  deployment.
- Or gate the refresh behind a `pg_try_advisory_lock` so only the pod that wins
  the lock refreshes.

**Rule:** *idempotent + DB-arbitrated (SKIP LOCKED)* → let every pod run it.
*Non-idempotent or expensive-once* → singleton via ShedLock/CronJob/advisory
lock.

### Optimistic & pessimistic locking (m10–m13) — 🟢 already cross-pod

**Why it holds:** These are enforced **in the database**. `@Version` optimistic
locking, `SELECT FOR UPDATE`, isolation levels, deadlock detection — all
arbitrated by the single DB, so they work identically whether the two
contending transactions are on the same pod or different pods. This is the
payoff of pushing coordination into the DB. 🟢

**The one caveat:** any lock you implement **in application memory** (a
`synchronized` block, a local `ReentrantLock`, an in-JVM `Semaphore`) coordinates
*only that pod*. Two pods enter the "critical section" simultaneously. If you
need a cross-pod mutex, it must be a **DB row lock** (`SELECT FOR UPDATE` on a
lock row / advisory lock) or a **Redis lock** (Redlock) — never a JVM lock.

### Read replicas (m21) — 🟡 lag is now everyone's problem

**What breaks:** With N pods, a user's write can land on the primary via pod A,
and their immediate follow-up read can hit pod B routed to a *replica* that
hasn't caught up → stale read. Sticky sessions don't help because the split is
primary-vs-replica, not pod-vs-pod.

**What to do:**
- The m21 read-your-writes fix generalizes: for a read that must reflect the
  caller's own recent write, route it to the **primary**, or gate on the
  replica's replay **LSN**. This logic must live in shared middleware, not one
  pod.
- The `AbstractRoutingDataSource` config must be **identical on every pod** —
  same replica endpoints, same routing rule.

### Sharding & distributed DB (m23, m24) — 🔴 config must be byte-identical

**What breaks:** The `ShardRouter` is deterministic **only if every pod computes
the same route**. If pod A has 4 shards configured and pod B (mid-rollout) has
5, or their consistent-hashing rings differ (different virtual-node count,
different seed), the **same key routes to different shards on different pods** —
writes and reads split-brain across shards. Silent data corruption.

**What to do:**
- Shard topology (shard list, ring config, virtual-node count, hash seed) must
  be **centralized config**, rolled out atomically — not baked per-pod and
  drifting.
- **Resharding while N pods run** is the hard case: during the window where pods
  disagree on topology, you get misroutes. Use a coordinated cutover (config
  flag flipped everywhere at once) or a routing layer (Citus/Vitess, m24) that
  owns topology *outside* the app entirely — which is a major reason to graduate
  from app-level sharding (m23 → m24).
- Citus (m24) moves this problem into the DB tier; the app just talks to the
  coordinator. The coordinator config is the single source of truth. 🟡

### Schema migrations (m18, m19, m20) — 🔴 rolling deploys demand expand/contract

**What breaks:** A rolling deploy runs **old and new app versions
simultaneously** for minutes. If the new version applies a breaking migration
(drop/rename a column) at startup, the still-running old pods break instantly —
or the new pods break against the old schema.

Also: **which pod runs the migration?** If every pod runs Flyway on boot, 20
pods race to migrate. (Flyway/Liquibase take a lock, so it's usually *safe* but
serialized — pods block on boot; and it's fragile.)

**What to do:**
- **Expand/contract (m18) is not optional at scale — it's the only safe path.**
  Every intermediate schema must satisfy *both* the old and new app version.
  Expand and contract are separate deploys, often days apart.
- Run migrations as a **pre-deploy step / k8s Job / init-container with leader
  election**, not on every pod's startup path. One migration run, then roll the
  app.
- Online DDL (m19) matters *more* at scale: `CREATE INDEX CONCURRENTLY` so the
  index build doesn't block the fleet's writes.
- Always-forward (m20): a rollback that assumes one instance is even more
  dangerous when 20 pods are mid-transition.

### CQRS / outbox (m27) — 🟢 designed for this (mostly)

**Why it holds:** Outbox write is in the same DB transaction (atomic regardless
of pod). The poller uses SKIP LOCKED (multi-pod safe). Idempotent projection via
`last_event_id` (safe under at-least-once and replays). This module is
essentially a **template for correct multi-instance eventing**. 🟢

**Remaining care:**
- Ordering *per aggregate* still matters — if two pods project events for the
  *same* aggregate concurrently they can apply out of order. Serialize per
  aggregate (hash aggregate → poller partition) or keep strict `ORDER BY id` +
  single-aggregate locking.
- The read model is shared (one DB), so all pods see the same read side — good.
  If the read model were per-pod (in-memory), it'd have the m25 divergence
  problem.

### Multi-tenancy (m30) — 🟡 tenant context is per-request, so mostly fine

**What breaks (if you're careless):** Tenant context (RLS `SET`, `search_path`)
is set **per request** and must be set on *whatever pod* handles the request.
That's inherently pod-local and per-request, so it scales fine — **as long as
every pod sets it on every request** and clears it before returning the
connection to the pool.

**The scale-specific hazard:** connection pooling. If you `SET search_path` /
`SET app.tenant_id` on a pooled connection and **don't reset it**, the next
request (possibly a different tenant, possibly on the same pod) inherits it →
**cross-tenant leak**. This is worse at scale because pooled connections churn
through many tenants.

**What to do:**
- Always set tenant context at the *start* of the request and reset it in a
  `finally` (or use `SET LOCAL` inside a transaction so it's scoped to the tx and
  auto-cleared on commit/rollback).
- RLS (m30 strategy 1) is the strongest defense precisely because it's
  DB-enforced and doesn't depend on every pod remembering the `WHERE`.
- Behind PgBouncer in *transaction* mode, session-level `SET` doesn't persist as
  you expect — use `SET LOCAL` within a transaction.

### ID / sequence generation (m08) — 🟢 already safe

**Why it holds:** DB sequences with `allocationSize` hand each pod a distinct
block of IDs. Pod A gets 1–50, pod B gets 51–100 — no collisions, just gaps.
This is inherently multi-instance safe. 🟢 (Never generate IDs from an in-memory
`AtomicLong` — that collides across pods. UUIDs or DB sequences only.)

### Query performance & JPA correctness (m01–m09) — 🟢 pod-count-neutral

Indexing, plans, N+1, fetch strategy, batching — these are properties of the
*query and the schema*, identical no matter how many pods issue them. Scaling
doesn't change them. 🟢 (Scaling *amplifies* them — an N+1 that's tolerable at
1 pod × low RPS becomes a DB-crushing flood at 20 pods × high RPS — but the fix
is the same fix.)

---

## Summary table

| Pattern | Modules | At N instances | Action |
|---|---|---|---|
| Connection pools | 15–17 | 🔴 pool × pods; connection storm | Size by budget/maxPods; **PgBouncer**; cap HPA |
| Bulkhead semaphore | 17 | 🔴 per-pod, not global | DB-side limit / role for a true global cap |
| In-process cache | 25 | 🔴 divergence across pods | **Redis shared L2**; pub/sub invalidation |
| Single-flight | 25 | 🟡 collapses per-pod only | Accept N-per-cold-key, or add Redis lock |
| Scheduled refresh | 26 | 🔴 runs on every pod | **ShedLock / CronJob / advisory lock** singleton |
| Outbox poller | 27 | 🟢 SKIP LOCKED cooperates | Keep per-aggregate ordering |
| DB locking / `@Version` | 10–13 | 🟢 DB-arbitrated | Never use JVM locks for cross-pod mutex |
| Read replica | 21 | 🟡 lag hits read-your-writes | Route own-writes to primary; identical config |
| Sharding | 23 | 🔴 config drift = misroute | Centralized atomic topology config |
| Citus | 24 | 🟡 coordinator owns topology | Graduate here when app-sharding hurts |
| Migrations | 18–20 | 🔴 rolling deploy = 2 versions | **Expand/contract**; migrate as a Job |
| CQRS/outbox | 27 | 🟢 built for it | Per-aggregate serialization |
| Multi-tenancy | 30 | 🟡 context per-request + pool reset | `SET LOCAL`; RLS; reset on return |
| ID generation | 08 | 🟢 sequence blocks per pod | Never in-memory counters |
| Query/JPA basics | 01–09 | 🟢 neutral (amplified) | Same fixes, higher stakes |

---

## The decision checklist before you scale to N

1. **Every pool sized against the DB's total connection budget ÷ maxReplicas?**
   Is PgBouncer in front? (m15/17)
2. **Any `@Scheduled` job that isn't idempotent + DB-arbitrated?** Make it a
   singleton (ShedLock / CronJob). (m26/27)
3. **Any cache in JVM heap that must be coherent?** Move it to Redis or add
   invalidation. (m25)
4. **Any mutex/limit/counter in JVM memory?** It's per-pod — move to DB/Redis or
   accept it's not global. (m11/17)
5. **Is every schema change expand/contract-safe for a rolling deploy?** Are
   migrations run once (Job), not per-pod-on-boot? (m18/19/20)
6. **Is shard/replica/topology config centralized and rolled out atomically?**
   (m21/23/24)
7. **Is tenant context set per-request and reset on connection return
   (`SET LOCAL`)?** (m30)

Pass all seven and the fleet behaves like the single instance you tuned. Miss
one and you get an incident that only reproduces at scale — the worst kind to
debug.
