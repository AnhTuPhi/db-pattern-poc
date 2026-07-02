# ISSUE — Why this repository exists

## The one-sentence problem

> **In a data-heavy backend service, the database is where correctness is won or lost — and almost every database failure is invisible in code review, only surfacing under production concurrency, data volume, or scale.**

This repository is the executable proof of that statement. Each of the 30
modules reproduces one specific way a database interaction goes wrong in
production, then demonstrates the fix with the actual SQL, the actual timing,
and the actual failure mode you can watch happen.

---

## The context that makes this urgent

This work backs **DAccount**, a financial account-management system for VN
Direct (Oracle 23ai in prod, Spring Boot 3.4 / Java 21). In a fintech context
the cost of getting the database wrong is not abstract:

| Failure class | Business consequence |
|---|---|
| Lost update / race on a balance | **Money is wrong.** Non-negotiable. |
| N+1 / bad plan under load | P95 latency spike → SLA breach → someone paged at 03:00 |
| Pool exhaustion / long transaction | One slow path takes the **whole service** down |
| Broken migration | Deploy outage, or worse, silent data corruption |
| Stale read after write | Customer sees a trade that "didn't happen" |
| Cross-pod cache divergence | Two users, same query, different answers |

None of these are exotic. They are the **default** behavior of naive code. You
have to actively engineer them out.

---

## Why "just read the docs" isn't enough

The gap this repo closes is that database pathologies **cannot be reasoned
about from source alone**:

1. **They're emergent.** `orderRepo.findAll()` then `order.getItems()` is
   correct Java. It only becomes an N+1 bug at runtime, at scale, against a
   real optimizer.
2. **They're concurrency-dependent.** A lost update needs two transactions
   interleaving in a specific window. You can't see it in a single-threaded
   test.
3. **They're data-dependent.** A query with a perfect plan on 10k rows picks a
   catastrophic plan on 10M rows when statistics go stale.
4. **The fixes trade off against each other.** JOIN FETCH, EntityGraph,
   @BatchSize, DTO projection all kill N+1 — but each breaks something else
   (pagination, cartesian products, write-back, cache cost). Knowing *which*
   fix fits is the actual skill.

A prose document asserts these. A **runnable POC proves them** — you seed the
data, hit the endpoint, and read the statement count / plan / lock graph
yourself.

---

## What "the issue" actually is, decomposed

The single issue — *"the database is the reliability frontier"* — decomposes
into ten problem families, each a group of modules:

| # | Problem family | The question it answers | Modules |
|---|---|---|---|
| 1 | **Query performance** | Why is this query slow, and how do I know? | 01–04 |
| 2 | **JPA correctness** | Why does the ORM fire 100 queries / delete the wrong row? | 05–07 |
| 3 | **JPA throughput** | Why is my bulk load 100× slower than it should be? | 08–09 |
| 4 | **Concurrency & locking** | Why is money wrong under concurrent writes? | 10–13 |
| 5 | **Transaction discipline** | Why did one long transaction degrade the whole DB? | 14 |
| 6 | **Connection management** | Why did the service die when the pool filled? | 15–17 |
| 7 | **Schema migration** | How do I change the schema without an outage? | 18–20 |
| 8 | **Scaling the data** | What do I do when one node/table isn't enough? | 21–24 |
| 9 | **Read-path scaling** | How do I serve reads cheaply without lying? | 25–27 |
| 10 | **Storage shape & tenancy** | Relational, document, or both? One tenant or many? | 28–30 |

---

## Scope

**In scope**
- Reproducing each pathology as a runnable Spring Boot module (port `82NN`).
- The measured fix, with the metric that proves it (`sqlStatements`, plan
  shape, lock graph, movement fraction, latency).
- Oracle-specific behavior where prod runs Oracle (modules 02, 03, 08, 19).
- The tech-debt / caveat of every fix — no fix is free.

**Explicitly out of scope**
- Being a framework or a library. These are teaching artifacts, not
  production code to import.
- Full observability stack wiring (Grafana dashboards, alert rules) beyond the
  compose file that stands the services up.
- Application-domain logic. The domains (orders, items, events) are the
  minimum needed to make each pathology reproducible.

---

## Definition of done, per module

A module is "done" when a reader can, from a clean checkout:

1. Start the required infra with one `docker compose --profile … up` command.
2. Run the module with one `./mvnw -pl NN-… spring-boot:run`.
3. Reproduce the **broken** behavior via a documented `curl`.
4. Reproduce the **fixed** behavior via a documented `curl`.
5. See a **number** (not a vibe) that proves the difference.
6. Read the **tech debt** the fix introduces.

---

## Where to go next

- **[TECHNICAL.md](TECHNICAL.md)** — per-module technical breakdown: the hard
  problem, what it protects, the solution shape, key tech by responsibility,
  how it solves each sub-problem, and the tech debt to acknowledge.
- **[CONSISTENCY.md](CONSISTENCY.md)** — what horizontal scaling (k8s pods / VM
  fleet) does to each of these patterns, and which ones silently break when you
  go from 1 replica to N.
- **[docs/explainer.html](docs/explainer.html)** — an interactive, filterable
  visual walkthrough of all 30 modules and the request/data flow.
- **[README.md](README.md)** — how to build and run everything.
