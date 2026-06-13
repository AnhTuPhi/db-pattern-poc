# 24 - DB Vitess / Citus (distributed SQL: when you outgrow app-level sharding)

> App-level sharding (m23) breaks down at some point.
> Distributed SQL — Vitess for MySQL, Citus for Postgres — moves the
> routing from your app into the database.

## Thesis

In m23 the application was the shard router: a `ShardRouter`
component held the topology and every query was rewritten to target
the right pool. That works fine until:

- you need to add or remove shards more than once a year
- cross-shard joins start showing up in your real query log
- DDL has to run "N times atomically" and "atomically" isn't a thing
- a new engineer keeps writing the wrong shard key by accident

At that point the right move is to delegate sharding to a database
that does it natively. Two production-grade options:

| Platform | Engine     | Maturity                  | Picks the shard? | Joins?                       |
|----------|------------|---------------------------|------------------|------------------------------|
| Vitess   | MySQL      | YouTube, Slack, GitHub    | VTGate proxy     | VIndex-based; engineered     |
| Citus    | Postgres   | Microsoft / Azure-owned   | Coordinator      | Co-located + reference tbls  |

This POC runs a **Citus 12** cluster locally — 1 coordinator + 2
workers — and shows what app-level sharding turns into when the
database is the one routing.

## Setup

```bash
cd ..
# Citus is a separate compose profile — it's heavy, so it's not part of "core".
docker compose --profile citus up -d
./mvnw -pl 24-db-vitess-citus -am spring-boot:run
```

App boots on **:8224**. Coordinator is on **:5433** (5432 is reserved
for the m01–m23 single-node Postgres).

If Citus isn't available, see the "Without Citus" section below — most
of the value of this module is in the design discussion.

## Run order

```bash
# 0. Install the extension + register workers. Idempotent.
curl -X POST 'localhost:8224/citus/setup' | jq

# 1. Create distributed + co-located + reference tables, seed.
curl -X POST 'localhost:8224/citus/seed?tenants=200&ordersPerTenant=10' | jq

# 2. Single-tenant — Task Count: 1.
curl 'localhost:8224/citus/single-tenant/42' | jq

# 3. Cross-tenant aggregate — Task Count: N (scatter/gather).
curl 'localhost:8224/citus/cross-tenant' | jq

# 4. Co-located join — local on each worker, no shuffle.
curl 'localhost:8224/citus/colocated-join' | jq

# 5. Reference-table join — orders ⨝ regions (regions is replicated everywhere).
curl 'localhost:8224/citus/reference-join' | jq

# 6. Topology metadata.
curl 'localhost:8224/citus/topology' | jq
```

## The three table types

### Distributed table
```sql
SELECT create_distributed_table('orders', 'tenant_id');
```
Sharded by `tenant_id`. Each shard is a real Postgres table on a worker
(`orders_102008`, `orders_102009`, …). The coordinator hashes
`tenant_id` to figure out which worker owns the shard.

### Co-located table
```sql
SELECT create_distributed_table('order_items', 'tenant_id',
                                colocate_with => 'orders');
```
Same distribution column, same shard count, matching shards on the
SAME worker. A join on `tenant_id` is local — no cross-worker shuffle.
**This is the most important Citus concept.** Plan your distribution
column around the joins you can't lose.

### Reference table
```sql
SELECT create_reference_table('regions');
```
Replicated in full to every worker. Joins to it are local. Use for
small slowly-changing dimensions (countries, currencies, regions).
Don't use for large or write-heavy tables — every write fan-outs.

## What the EXPLAIN looks like

### Single-tenant
```
Custom Scan (Citus Adaptive)  (cost=...)
  Task Count: 1
  Tasks Shown: All
  ->  Task
        Node: host=citus-worker-1 port=5432
        ->  Aggregate
              ->  Index Only Scan on orders_102008
```
**One worker. One task. OLTP speed.**

### Cross-tenant
```
Custom Scan (Citus Adaptive)  (cost=...)
  Task Count: 32
  Tasks Shown: One of 32
  ->  Task
        ->  Aggregate
              ->  Seq Scan on orders_102008
```
**32 parallel tasks. Coordinator combines the partial aggregates.**

### Co-located join
```
Custom Scan (Citus Adaptive)
  Task Count: 32
  ->  Task
        ->  HashAggregate
              ->  Hash Join
                    Hash Cond: (oi.order_id = o.id)
                    ->  Seq Scan on order_items_102040 oi
                    ->  Hash
                          ->  Seq Scan on orders_102008 o
```
**The join is INSIDE the task — local on the worker. No cross-worker network.**

## Vitess in three sentences

Vitess is the same idea for MySQL. **VTGate** is the routing proxy
(equivalent to the Citus coordinator), **VTTablet** wraps each MySQL
shard (the workers), and a **topology server** (etcd or zk) holds the
shard catalog. You define your sharding scheme via VSchema JSON,
including the **VIndex** type (hash, lookup, etc.) and **keyspace**
co-location rules. The app speaks MySQL protocol to VTGate as if it
were one big MySQL.

Used by: YouTube, Slack, GitHub, HubSpot, PlanetScale. Mature, but
heavier to operate. Pick Vitess if you're already on MySQL and your
volume is YouTube-scale.

## Citus in three sentences

Citus is a Postgres extension that turns one coordinator + N workers
into a distributed Postgres cluster. Distributed tables are sharded by
hash; co-located tables share the distribution scheme; reference
tables are replicated. SQL works as-is for the supported subset
(roughly: anything with the distribution column in the WHERE clause is
fine; anything that requires a global serializable view across workers
is not).

Used by: Microsoft (it's now part of Azure Postgres), HEAP, Heap,
CloudFlare. Pick Citus if you're on Postgres and the queries you want
to keep are already mostly "single tenant".

## When to graduate from m23 → m24

| Sign                                                           | What it means                            |
|----------------------------------------------------------------|-------------------------------------------|
| You reshard more than once / quarter                           | Topology change is too expensive to be app-side. |
| You have ≥3 services that need to know the shard topology       | Centralize routing in the DB.            |
| Cross-tenant queries are now a documented requirement           | Scatter/gather as a feature, not a hack. |
| Schema migrations regularly partial-fail across shards          | The DB needs to coordinate, not your job runner. |
| You can't reason about the data placement anymore               | Time to make placement a property of the schema. |

Counter-signs (stay app-level):

| Sign                                            | Why                                             |
|-------------------------------------------------|-------------------------------------------------|
| Cross-tenant queries are forbidden by policy    | App-level sharding is simpler and you're fine. |
| You're nowhere near one-node Postgres limits    | Don't take on distributed-SQL ops for nothing. |
| Your team has no Citus/Vitess operations skill  | Operating these is non-trivial. Budget for it. |

## What Citus does NOT do

- **Cross-shard transactions are limited.** 2PC exists but is opt-in
  and slow. Write your operations to be single-shard whenever
  possible.
- **Distributed serializable isolation is hard.** Per-shard isolation
  is honest. Multi-shard reads can see partial state mid-write.
- **DDL has gotchas.** Some Postgres DDL works on distributed tables;
  some doesn't. Check the docs page per operation.
- **No magic.** If your queries don't filter by the distribution
  column, every query is a scatter/gather. You haven't escaped the
  m23 trade-off — you've just moved it into a place that handles it
  better.

## Without Citus — concept-only

The Spring Boot app needs `docker compose --profile citus up -d` to
run. Without it, the `/citus/setup` endpoint will fail with "extension
citus does not exist".

The code is still useful to read for:
- the DDL pattern (`create_distributed_table`, `create_reference_table`)
- the EXPLAIN shape (`Custom Scan (Citus Adaptive)`, `Task Count: N`)
- the catalogue queries (`pg_dist_node`, `pg_dist_partition`, `pg_dist_shard`)

## Production checklist

| Symptom                                          | Likely cause                                | Fix                                                            |
|--------------------------------------------------|---------------------------------------------|----------------------------------------------------------------|
| Every query is "Task Count: N" → slow             | Hot queries don't filter by dist column     | Pick a different dist column. Or accept it (data analytics).   |
| Joins blow up                                    | Tables not co-located                       | `colocate_with =>` when creating; or move to reference table.   |
| Reference table is slow to write                 | Replicated to every worker on every write   | Don't put hot tables in reference. Switch to distributed.      |
| Mid-deploy: one worker missing the new column    | DDL didn't replicate                        | Wait for Citus DDL propagation; alert on schema drift.         |
| Worker disk fills before others                  | Skewed dist column (super-tenant)           | Re-shard the super-tenant alone; or move it to its own shard.  |
| Cross-shard write torn at failure                | Single-shard txn assumption broken          | Use 2PC (`citus.multi_shard_modify_mode = 'sequential'`).      |

## Files

```
src/main/java/com/claude/dbpoc/m24/
├── Application.java
├── service/
│   └── CitusService.java          # setup, seed, single/cross/colocated/reference, topology
└── web/
    └── CitusController.java       # /citus/{setup,seed,single-tenant,cross-tenant,...}
src/main/resources/
└── application.yml                # port 8224, coordinator on localhost:5433
```

## Related modules

- **[m21 - read-replica](../21-db-read-replica/)** — Citus workers can
  each have their own replica; Vitess has VTOrc for the same.
- **[m22 - table-partitioning](../22-db-table-partitioning/)** —
  partitioning + Citus is fine; each shard can itself be partitioned.
- **[m23 - sharding](../23-db-sharding/)** — the application-level
  version of this. Read m23 first; this module is "we outgrew it".
- **[m27 - cqrs](../27-db-cqrs/)** — a different answer to the
  cross-shard query problem.
- **[m30 - multi-tenancy](../30-db-multi-tenancy/)** — Citus's
  distribution-by-tenant is one of the textbook multi-tenant patterns.
