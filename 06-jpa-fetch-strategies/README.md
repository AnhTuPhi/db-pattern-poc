# 06 - JPA Fetch Strategies (Eager vs Lazy, OSIV, LIE)

> "Make it EAGER" is not a fix. This module shows why — and what the actual fixes look like.

## Thesis

**Fetch strategy is a property of the use case, not the entity.**

EAGER on the entity hard-codes a join into every query that touches it. The
caller doesn't get a vote. A profile page loads orders + customer + addresses
even when it only needed the id and total. Multiply by request volume and
you're paying for joins nobody asked for.

The right defaults are:

- `LAZY` on every association (especially `@ManyToOne`, which defaults `EAGER`)
- `LEFT JOIN FETCH` / `@EntityGraph` when the use case actually needs the graph
- DTO projection for read paths that go straight to JSON

## Setup

```bash
cd ..
docker compose --profile postgres up -d
./mvnw -pl 06-jpa-fetch-strategies -am spring-boot:run
```

App boots on **:8206**.

## Run order

```bash
# 1. Seed a tiny graph (5 customers x 3 orders x 4 items = 60 items).
curl -X POST 'localhost:8206/seed'

# 2. The EAGER-by-default trap.
curl 'localhost:8206/eager-vs-lazy/eager-trap'   | jq
curl 'localhost:8206/eager-vs-lazy/compare'      | jq

# 3. LazyInitializationException — the disease and the cures.
curl 'localhost:8206/lie/outside-tx'             | jq
curl 'localhost:8206/lie/with-join-fetch'        | jq
curl 'localhost:8206/lie/with-dto'               | jq

# 4. OSIV trade-off: same call, different profile.
./mvnw -pl 06-jpa-fetch-strategies spring-boot:run \
       -Dspring-boot.run.profiles=osiv-on &
curl 'localhost:8206/lie/with-osiv'              | jq
```

## What each endpoint proves

### `/eager-vs-lazy/eager-trap`

`orderRepo.findById(1)` — the developer thinks "one SELECT for one row".
With `Order.customer` marked `EAGER` (the JPA default for `@ManyToOne`),
Hibernate emits a JOIN to `customer`. With `OrderItem.order` ALSO `EAGER`,
loading an `OrderItem` chains through to both `Order` AND `Customer` — three
tables read for what the call site believed was a one-row read.

### `/eager-vs-lazy/compare`

Two reads against the same data:

| Read       | What happens                                                |
|------------|-------------------------------------------------------------|
| `findAll()`| EAGER joins fire. SQL pulls customer columns nobody read.   |
| DTO query  | Narrow SELECT, only the columns we need. No joins for nothing.|

The point: the entity's EAGER annotation made the choice for *every* caller.
The DTO let *this* use case decide.

### `/lie/outside-tx` — the `LazyInitializationException` reproduction

The classic sequence:

1. A `@Transactional` service method returns an `Order` without `JOIN FETCH`.
2. The transaction commits, the Hibernate Session closes.
3. The controller (running OUTSIDE the tx) touches `order.getItems()`.
4. The collection is a lazy proxy with no live session → **LIE**.

The endpoint catches the exception and reports it so the demo doesn't 500.
The exception IS the lesson — don't "fix" it by hand-waving with OSIV.

### `/lie/with-osiv` — the trade-off

Run with `--spring.profiles.active=osiv-on` and the same code that throws
LIE now works. Look at `statements_in_view` — every lazy getter you touched
in the view layer ran a separate SELECT. You traded a clean exception for
hidden N+1s.

OSIV is not a fix. It's a way to make the bug silent.

### `/lie/with-join-fetch` — correct fix #1

```java
@Query("SELECT o FROM Order o LEFT JOIN FETCH o.items WHERE o.id = :id")
Optional<Order> findByIdWithItems(Long id);
```

The collection is fully initialised inside the tx. The controller iterates
freely with zero new SELECTs. This is the right fix when you actually need
the entity graph.

### `/lie/with-dto` — correct fix #2

The DTO carries exactly the data the endpoint needs. No proxies, no LIE
possible. The bug is structurally impossible by construction. This is the
right default for read paths that go straight to JSON.

## The cost of OSIV (from a real-world counter)

With OSIV ON and a controller that touches `order.getCustomer().getName()`,
`order.getItems()`, and `order.getCustomer().getAddresses()`:

| Layer        | Statements |
|--------------|------------|
| Inside tx    | 1 (the original findById) |
| In view      | 3 (one per lazy getter) |

That's a 4× round-trip multiplier on every endpoint that touches more than
one association. Multiply by request rate and OSIV is one of the most
expensive defaults a JPA project can leave on.

## Configuration that matters

```yaml
spring:
  jpa:
    open-in-view: false              # The correct production default.
    properties:
      hibernate:
        default_batch_fetch_size: 0  # We want CLEAN SQL counts here.
                                     # Module 05 covers the batching story.
```

## Files

```
src/main/java/com/claude/dbpoc/m06/
├── Application.java               # @SpringBootApplication + SqlCounter wiring
├── domain/
│   ├── Customer.java              # LAZY collections (correct)
│   ├── Order.java                 # @ManyToOne EAGER (the trap)
│   ├── OrderItem.java             # @ManyToOne EAGER (chained)
│   └── Address.java               # @ManyToOne LAZY (the right shape)
├── repo/
│   ├── OrderRepository.java       # findAll, @EntityGraph, JOIN FETCH, DTO
│   ├── CustomerRepository.java
│   └── OrderItemRepository.java
├── dto/
│   ├── OrderSummaryDto.java       # record — no proxies, no LIE possible
│   └── OrderSummaryProjection.java
└── web/
    ├── SeedController.java        # POST /seed
    ├── EagerVsLazyController.java # /eager-vs-lazy/*
    ├── LazyExceptionController.java # /lie/*
    └── OrderLoaderService.java    # the @Transactional boundary

src/main/resources/
├── application.yml                # open-in-view: false (correct default)
└── application-osiv-on.yml        # for the contrast demo
```

## Production checklist for `@ManyToOne` / `@OneToMany`

| If you find this in code | Replace with |
|--------------------------|--------------|
| `@ManyToOne` (no fetch)  | `@ManyToOne(fetch = FetchType.LAZY)` — always |
| `@OneToMany(fetch = EAGER)` | `LAZY` + JOIN FETCH at the call site |
| `open-in-view: true`     | `false`, then audit for LIEs and add JOIN FETCH / DTO |
| Entity returned from controller | DTO. Always. The entity escapes its tx — that's the bug class this module covers. |
