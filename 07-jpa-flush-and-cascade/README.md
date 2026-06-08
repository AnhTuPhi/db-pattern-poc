# 07 - JPA Flush, Dirty Checking, and Cascade

> Three of the most expensive habits in a JPA codebase live in this module:
> "I'll just use cascade=ALL", "I'll just call save()", and "I don't worry
> about flushing — Hibernate handles it". The endpoints below show what
> Hibernate actually does, in SQL counts you can read off the response.

## Thesis

There are three flavours of "I thought my code did X" in production JPA code:

1. **Cascade surprises.** `cascade=ALL` shipped REMOVE without you noticing.
   Deleting one parent silently fired N child DELETEs.
2. **Flush surprises.** "I never called `flush()`" — and you didn't have to.
   The default `FlushMode.AUTO` flushes before any query that *might* read
   the modified entity. Most of the time that's invisible; sometimes it's
   the difference between getting an ID back or a NULL.
3. **Dirty-check surprises.** Every flush walks every managed entity. Loading
   1,000 entities to update 1 of them is paying 1,000 entities of CPU on
   every UPDATE.

This module isolates each surprise behind one endpoint, so the cost shows
up in the response body and you don't have to guess.

## Setup

```bash
cd ..
docker compose --profile postgres up -d
./mvnw -pl 07-jpa-flush-and-cascade -am spring-boot:run
```

App boots on **:8207**.

## Run order

```bash
# 1. Seed the parent/child trees and the Customer→Account→Transaction graph.
curl -X POST 'localhost:8207/seed?parentCount=5&childrenPer=4&customers=200&accountsPer=3&transactionsPer=5'

# 2. Cascade — the friendly side, then the dark side.
curl -X POST 'localhost:8207/cascade/save-parent'             | jq
curl -X POST 'localhost:8207/cascade/delete-parent'           | jq
curl -X POST 'localhost:8207/cascade/orphan-removal'          | jq
curl -X POST 'localhost:8207/cascade/orphan-removal-trap'     | jq
curl -X POST 'localhost:8207/cascade/safe-pattern'            | jq

# 3. Flush — when does the INSERT actually leave the JVM?
curl -X POST 'localhost:8207/flush/auto-trigger'              | jq
curl -X POST 'localhost:8207/flush/commit-mode'               | jq
curl -X POST 'localhost:8207/flush/explicit'                  | jq
curl -X POST 'localhost:8207/flush/no-flush-for-unrelated'    | jq
curl -X POST 'localhost:8207/flush/ordering'                  | jq

# 4. Dirty checking — what does session size cost you?
curl 'localhost:8207/dirty-check/large'                       | jq
curl 'localhost:8207/dirty-check/one-entity'                  | jq
curl 'localhost:8207/dirty-check/no-change'                   | jq
curl 'localhost:8207/dirty-check/dto-readonly'                | jq
```

## What each section proves

### Cascade — `/cascade/*`

`BadParent` uses `cascade = CascadeType.ALL, orphanRemoval = true`. It looks
generous; it is a trap.

| Endpoint                          | What it shows                                                            |
|-----------------------------------|--------------------------------------------------------------------------|
| `/cascade/save-parent`            | The friendly half: PERSIST cascades down. 1 INSERT becomes 1+N INSERTs.  |
| `/cascade/delete-parent`          | The dark half: REMOVE cascades down. 1 DELETE becomes 1+N DELETEs.       |
| `/cascade/orphan-removal`         | `getChildren().clear()` does the right thing — mutates the same List.    |
| `/cascade/orphan-removal-trap`    | `setChildren(new ArrayList<>())` deletes *every* previous child.         |
| `/cascade/safe-pattern`           | `GoodParent` uses `{PERSIST, MERGE}`. Delete fails on FK — that's the safety. |

**The rule:** `cascade=ALL` is rarely what you want. Spell out the cascade
types you actually need (`PERSIST`, `MERGE`) and let the database stop you
from deleting things you didn't plan to.

**On `orphanRemoval`:** the bug is the reference swap, not the annotation.
If you find yourself writing `entity.setChildren(...)`, stop. Use
`entity.getChildren().clear()` followed by `addAll(...)` — Hibernate diffs
the same collection instance and emits the right DELETE/INSERT pairs.

### Flush — `/flush/*`

| Endpoint                          | Demonstrates                                                              |
|-----------------------------------|---------------------------------------------------------------------------|
| `/flush/auto-trigger`             | Default `AUTO`: query against the same entity type forces a flush first.  |
| `/flush/commit-mode`              | `FlushMode.COMMIT`: query runs against the DB-only view; misses pending.  |
| `/flush/explicit`                 | `em.flush()`: synchronous, gets you the generated ID *now*.               |
| `/flush/no-flush-for-unrelated`   | AUTO is smart: queries against unrelated tables don't trigger flush.      |
| `/flush/ordering`                 | `hibernate.order_inserts=true` groups INSERTs by entity type for batching.|

**The rule:** Default to `AUTO`. Use `COMMIT` only when you've proven the
read consistency loss is acceptable (read-mostly batch jobs, hot reporting
paths). Reach for explicit `em.flush()` when you need a generated key
*before* the transaction ends.

### Dirty checking — `/dirty-check/*`

This is the section that changes how you write services. Run `/dirty-check/large`
after seeding `customers=1000` and compare `flushMs` to `/dirty-check/one-entity`.
Same UPDATE; one is single-digit ms, the other is not.

| Endpoint                          | Entities in session | Entities mutated | What it measures                       |
|-----------------------------------|---------------------|------------------|-----------------------------------------|
| `/dirty-check/large`              | N (all customers)   | 1                | O(N) dirty-check pass for one UPDATE    |
| `/dirty-check/one-entity`         | 1                   | 1                | Baseline: what one UPDATE should cost   |
| `/dirty-check/no-change`          | N                   | 0                | Pure dirty-check cost with zero writes  |
| `/dirty-check/dto-readonly`       | 0                   | 0                | DTO projection skips the context entirely |

**The rule:** A service method should load only what it needs to change.
`findAll()` followed by mutating one element is paying full-session CPU
for a one-row update. If you only mutate one row, fetch one row. If you
only read, use a DTO projection and mark the transaction `readOnly=true`
to skip flush altogether.

## Configuration that matters

```yaml
spring:
  jpa:
    open-in-view: false              # See module 06.
    properties:
      hibernate:
        generate_statistics: true    # Independent corroboration of SqlCounter.
        format_sql: true             # Read flush order in the p6spy log.
        jdbc:
          batch_size: 50             # Otherwise order_inserts saves nothing.
        order_inserts: true          # Group INSERTs by entity type.
        order_updates: true          # Same for UPDATEs.
```

## Files

```
src/main/java/com/claude/dbpoc/m07/
├── Application.java
├── config/
│   └── SqlCounterConfig.java
├── domain/
│   ├── BadParent.java               # cascade=ALL + orphanRemoval — the trap
│   ├── BadChild.java
│   ├── GoodParent.java              # cascade={PERSIST, MERGE} — the safe shape
│   ├── GoodChild.java
│   ├── Customer.java                # PERSIST only; root of the dirty-check graph
│   ├── Account.java
│   └── Transaction.java             # @Table(name="txn") — Postgres-reserved word workaround
├── repo/                            # Spring Data JPA repos for each domain
├── SeedController.java              # POST /seed
├── CascadeDemoController.java       # /cascade/*
├── FlushDemoController.java         # /flush/*
└── DirtyCheckController.java        # /dirty-check/*
```

## Production checklist

| Bad smell                                       | Replace with                                                |
|-------------------------------------------------|-------------------------------------------------------------|
| `cascade = CascadeType.ALL`                     | Enumerate: usually `{PERSIST, MERGE}`. Add REMOVE only when proven safe. |
| `orphanRemoval = true` + `setChildren(...)`     | `getChildren().clear(); getChildren().addAll(...)`          |
| Service does `findAll()` then mutates one row   | `findById(...)` (or a derived finder) and mutate that.      |
| Read-only endpoint in a `@Transactional` method | Add `readOnly = true` so flush is skipped at commit.        |
| Returning entities from a service to the controller | Return a DTO. The context closes; the entity becomes a footgun (see module 06). |
