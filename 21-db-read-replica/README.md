# 21 - DB Read Replica (and the "read your writes" bug)

> Routing reads to a replica is half the work.
> The other half is knowing when **not** to.

## Thesis

A read replica is asynchronous by default. The instant the primary
commits, the replica is **behind**, until WAL ships and applies.
Milliseconds usually, seconds under load, minutes when something is
wrong. If your app reads from the replica immediately after a write,
it can see the **pre-write** value — read-your-writes is broken.

Two ingredients:

1. **Routing**: Spring's `AbstractRoutingDataSource` keyed by
   `TransactionSynchronizationManager.isCurrentTransactionReadOnly()`
   sends `@Transactional(readOnly=true)` calls to the replica and
   everything else to the primary.
2. **Sticky-primary window**: after a write, pin reads from the same
   user/session to the primary for `replicaLagSLA` ms (or until the
   replica's LSN catches up). Past the window, fall back to replica.

You need both. (1) alone is broken for read-after-write. (2) alone
defeats the point of having a replica.

## Setup

```bash
cd ..
docker compose --profile postgres up -d
./mvnw -pl 21-db-read-replica -am spring-boot:run
```

App boots on **:8221**. Two Hikari pools, two schemas:

| Pool          | Schema        | Purpose                      |
|---------------|---------------|------------------------------|
| `m21-primary` | `m21_primary` | writes + read-after-write    |
| `m21-replica` | `m21_replica` | normal reads (read-only=true)|

The schemas live in the same Postgres for the POC. The routing code is
identical to a real two-server setup.

## Run order

```bash
# 0. Seed: alice $1000 on both schemas.
curl -X POST 'localhost:8221/replica/seed' | jq

# 1. Write — routed to PRIMARY.
curl -X POST 'localhost:8221/replica/write?id=1&balance=2000' | jq

# 2. Read — routed to REPLICA. Returns 1000 (stale!) because the demo
#    only replicates on explicit /lag-demo call.
curl 'localhost:8221/replica/read?id=1' | jq

# 3. Re-seed, then run the LAG bug demo.
curl -X POST 'localhost:8221/replica/seed' | jq
curl 'localhost:8221/replica/lag-demo?id=1&balance=2000&lagMs=300' | jq

# 4. Re-seed, then run the STICKY-READ fix.
curl -X POST 'localhost:8221/replica/seed' | jq
curl 'localhost:8221/replica/sticky-read?id=1&balance=2000&lagMs=300&stickyWindowMs=500' | jq
```

## What each endpoint proves

### `POST /replica/write`
Default `@Transactional` → `readOnly=false` → `RoutingDataSource`
picks `PRIMARY`. The write goes to `m21_primary.account`. No replica
side effect — replication is async.

### `GET /replica/read`
`@Transactional(readOnly=true)` → `readOnly=true` → routing picks
`REPLICA`. Hikari's `read-only: true` on the replica pool is a belt:
any accidental write here would error.

### `GET /replica/lag-demo`  ← the bug
The sequence the user actually experiences:

1. `t=0`: app writes balance=2000 to primary.
2. `t=0+ε`: app reads replica → returns `1000` (the pre-write value).
3. `t=300ms`: replication catches up; replica now returns `2000`.

The response shows `replicaReadAtT0` vs `replicaReadAfterLag` —
the gap is the bug.

In production this is the classic "I clicked save, refreshed, my
change disappeared, then came back 2 seconds later" support ticket.

### `GET /replica/sticky-read`  ← the fix
Same race, but immediately after the write we set
`RoutingDataSource.FORCE_PRIMARY = true` on the thread for
`stickyWindowMs` ms. Subsequent reads — even those marked
`@Transactional(readOnly=true)` — go to the primary. After the window,
the flag is cleared and reads return to the replica.

The response shows `readDuringStickyWindow` returns the just-written
value, and `readAfterStickyWindow` correctly reads from the replica
(which has caught up).

## Production patterns

### Sticky-primary scoping

- **Per request**: trivial — set FORCE_PRIMARY in a `@PostMapping`
  before it returns. But a follow-up GET from the browser is a NEW
  request on a NEW thread; the flag is gone.
- **Per session**: set a `read-from-primary` cookie or session
  attribute with an expiry. A gateway / interceptor reads it and
  primes FORCE_PRIMARY for the call.
- **Per user, distributed**: write the user's last-write LSN to Redis
  / a JWT claim with TTL = SLO. Reads check Redis; if a recent write
  is recorded, go primary.

### Wait-for-LSN (Postgres ≥ 10)

The strongest correctness:

```sql
-- on write commit, capture the LSN:
SELECT pg_current_wal_lsn();   -- e.g. 0/1B5C2378

-- on read, before querying the replica:
SELECT pg_wal_replay_wait_for_lsn('0/1B5C2378', '5s');
-- waits up to 5s for the replica to catch up to that LSN. If it
-- doesn't, you can fall back to primary or error.
```

Latency cost: the wait. But it's the only solution that's *literally*
correct, not just "probably correct".

### What never works

- **Read from primary always for "safety"**: defeats the replica. Same
  load on primary, more replication churn.
- **Synchronous replication** (`synchronous_commit=remote_apply`):
  writes block until the replica applies. Slow writes; primary stalls
  if replica is slow. Use only when correctness is non-negotiable AND
  you understand the failure modes.
- **"Just retry the read"**: the retry hits the same lag; you need
  jitter + backoff + a primary fallback.

## How the routing works

```
       ┌───────────────────────────────┐
       │  @Transactional(readOnly=?)   │  ← tx manager sets the flag
       └───────────────┬───────────────┘
                       │
                       ▼
       ┌───────────────────────────────┐
       │  LazyConnectionDataSourceProxy │  ← defers getConnection() until
       │                                │    the readOnly flag is set
       └───────────────┬───────────────┘
                       │
                       ▼
       ┌───────────────────────────────┐
       │  RoutingDataSource             │  ← determineCurrentLookupKey()
       │  ─ FORCE_PRIMARY ?             │     returns PRIMARY or REPLICA
       │  ─ TSM.isCurrentTxReadOnly()?  │
       └───────┬───────────────┬───────┘
               │PRIMARY        │REPLICA
               ▼               ▼
        ┌──────────┐    ┌──────────┐
        │ primaryDs│    │ replicaDs│
        │ (HikariCP)│   │ (HikariCP,│
        │          │    │  read-only)│
        └──────────┘    └──────────┘
```

The `LazyConnectionDataSourceProxy` is **non-negotiable**. Without it,
`getConnection()` is called BEFORE the tx manager has applied
`readOnly`, and every call routes to PRIMARY. With it, the real lookup
is deferred to the first JDBC operation, by which time the flag is set.

## The `@Transactional(readOnly=true)` invariant

```java
// ✅ correct
@Transactional(readOnly = true)
public AccountDto get(Long id) { ... only reads ... }

// ❌ wrong — writes inside a readOnly tx
@Transactional(readOnly = true)
public Account save(Account a) {
    return accountRepo.save(a);   // routed to REPLICA, which is read-only
}                                 // → exception, OR worse, silent no-op
                                  //   if the replica isn't actually read-only.

// ❌ wrong — read-modify-write inside readOnly
@Transactional(readOnly = true)
public BigDecimal getAndStamp(Long id) {
    Account a = accountRepo.findById(id).orElseThrow();
    a.setLastReadAt(Instant.now());   // dirty checking will TRY to update
    return a.getBalance();
}
```

Rule: `readOnly=true` is a *commitment* to read-only behavior. The
routing is the prize for honoring it.

## Replica lag observability

```sql
-- on the replica:
SELECT now() - pg_last_xact_replay_timestamp() AS replication_lag;
-- → interval, the time since the last applied tx on this replica

-- on the primary:
SELECT client_addr,
       state,
       sent_lsn,
       write_lsn,
       flush_lsn,
       replay_lsn,
       sent_lsn - replay_lsn AS bytes_behind
FROM pg_stat_replication;
-- → one row per connected replica, showing how far behind each is
```

Alert on `replication_lag > slo` (e.g. 5s). Below the SLO you can
serve from the replica; above it, route everything to primary or shed
load.

## Production checklist

| Symptom                                       | Likely cause                              | Fix                                                  |
|-----------------------------------------------|-------------------------------------------|------------------------------------------------------|
| "I saved it but the page shows old data"      | Replica routing without sticky window     | Sticky-primary for the SLO window; or wait-for-LSN.  |
| Lag spike during deploy                       | Big tx blocks WAL apply on replica        | Shorten tx (m14); replica IO won't help.             |
| Replica lag grows forever                     | Long tx on primary pinning xmin           | m14 — find and shorten the long tx.                  |
| `@Transactional(readOnly=true)` ignored       | Forgot LazyConnectionDataSourceProxy      | Add the lazy proxy in DataSourceConfig.              |
| Replica accidentally taking writes            | Hikari pool not marked read-only          | `read-only: true` on the replica pool.               |
| Read-your-writes broken for a specific user   | Sticky cookie / session not propagated    | Check gateway / interceptor; verify TTL.             |

## Files

```
src/main/java/com/claude/dbpoc/m21/
├── Application.java
├── DataSourceConfig.java              # primary + replica + RoutingDataSource + lazy proxy
├── routing/
│   └── RoutingDataSource.java         # the lookup logic
├── domain/Account.java
├── repo/AccountRepository.java
├── service/
│   └── ReplicaService.java            # write, read, lagDemo, stickyRead, seed
└── web/
    └── ReplicaController.java         # /replica/{seed,write,read,lag-demo,sticky-read}
src/main/resources/
├── application.yml                    # two HikariCP pools, replica read-only
└── schema.sql                         # creates m21_primary + m21_replica schemas
```

## Related modules

- **[m14 - long-transaction](../14-db-long-transaction/)** — long tx
  on the primary is the #1 cause of unbounded replica lag.
- **[m17 - pool-exhaustion](../17-db-pool-exhaustion/)** — splitting
  primary/replica pools is bulkheading at the storage layer.
- **[m27 - cqrs](../27-db-cqrs/)** — when you want a different SHAPE
  of read model, not just a copy.
