# 09 - JPQL vs Criteria vs QueryDSL vs Native SQL

> One dynamic search query. Four implementations. Same input, same SQL,
> same DTO. Compare line count, refactor risk, and what shows up in the
> SQL log — then pick a house rule for your team.

## Thesis

Most "which builder?" debates in JPA codebases never put the four
implementations side-by-side with the same input. This module does.

The query is realistic: paginated list-search over orders, with seven
optional filters (customer name LIKE, status IN, date range, amount
range, country =) and configurable sort. That's the exact shape of every
admin/back-office list screen on Earth.

The four implementations:

| Implementation | Type-safe? | Verbose? | DB-specific? | Best for                                       |
|----------------|------------|----------|--------------|------------------------------------------------|
| **JPQL**       | No         | Low      | No           | Static-ish queries; SQL in plain sight.        |
| **Criteria**   | Partial[^1]| High     | No           | Spec-only constraint, no QueryDSL allowed.     |
| **QueryDSL**   | Yes        | Medium   | No           | Dynamic-filter list screens. Default choice.   |
| **Native**     | No         | Low      | Yes          | DB-specific features (CTE, LATERAL, JSONB).    |

[^1]: Compile-time root/path types, but `.get("name")` strings stay
unsafe unless you wire the JPA metamodel.

## Setup

```bash
cd ..
docker compose --profile postgres up -d
./mvnw -pl 09-querydsl-vs-jpql-vs-criteria -am compile      # generates QOrder etc.
./mvnw -pl 09-querydsl-vs-jpql-vs-criteria spring-boot:run
```

App boots on **:8209**.

> **First time:** if your IDE flags `QOrder` / `QCustomer` as unresolved,
> the APT processor hasn't run yet. Run `mvn compile` once and reimport;
> the Q-classes land in `target/generated-sources/annotations`.

## Run order

```bash
# 1. Seed customers, orders, items (deterministic — seed=42).
curl -X POST 'localhost:8209/seed?customers=100&ordersPer=10&itemsPer=3'

# 2. Hit one impl at a time.
curl 'localhost:8209/search/jpql?customerName=a&country=US&size=10'      | jq
curl 'localhost:8209/search/criteria?customerName=a&country=US&size=10'  | jq
curl 'localhost:8209/search/querydsl?customerName=a&country=US&size=10'  | jq
curl 'localhost:8209/search/native?customerName=a&country=US&size=10'    | jq

# 3. The headline — all four against the same input, with a cross-check.
curl 'localhost:8209/compare?customerName=a&country=US&size=10' | jq

# 4. With status IN, date range, amount range:
curl 'localhost:8209/compare?statuses=PAID,SHIPPED&minAmount=100&maxAmount=500&size=5' | jq
```

## What `/compare` returns

```json
{
  "criteria": { "customerName": "a", "country": "US" },
  "pageable": { "page": 0, "size": 10, "sort": "id,desc" },
  "results": [
    { "impl": "jpql",     "elapsedMs": 4.2, "totalElements": 47, "page": [...] },
    { "impl": "criteria", "elapsedMs": 5.1, "totalElements": 47, "page": [...] },
    { "impl": "querydsl", "elapsedMs": 3.9, "totalElements": 47, "page": [...] },
    { "impl": "native",   "elapsedMs": 3.5, "totalElements": 47, "page": [...] }
  ],
  "allImplsAgreeOnIds": true
}
```

`allImplsAgreeOnIds: true` is the assertion. If any implementation drifts
(wrong filter, wrong ORDER BY, wrong paging), `false` flips and the JSON
shows which list disagrees with the others. That's the test you actually
want to ship — not microbenchmarks.

## The four implementations, head-to-head

### `jpql/JpqlOrderSearch.java`

```java
StringBuilder jpql = new StringBuilder()
    .append("SELECT new com.claude.dbpoc.m09.domain.OrderSummary(...) ")
    .append("FROM Order o JOIN o.customer c ")
    .append(where);  // built from a list of clauses + a params map
```

- **Win:** the JPQL is right there. Anyone on the team reads SQL.
- **Loss:** rename `Order.status` → JPQL is "fine" until runtime. The
  compiler doesn't help.
- **Subtle trap:** the WHERE-vs-AND state machine. Use `clauses.isEmpty()
  ? "" : " WHERE " + String.join(" AND ", clauses)` — that single line
  saves the "WHERE WHERE" / "AND AND" class of bugs.

### `query/CriteriaOrderSearch.java`

```java
CriteriaQuery<OrderSummary> cq = cb.createQuery(OrderSummary.class);
Root<Order> o = cq.from(Order.class);
Join<Order, Customer> cust = o.join("customer");
cq.select(cb.construct(OrderSummary.class, o.get("id"), cust.get("name"), ...));
```

- **Win:** type-checked at the Path / Predicate level; standard JPA.
- **Loss:** verbose. Every column reference is a method call. The COUNT
  query *cannot reuse the SELECT roots* — you rebuild the predicate tree
  against a fresh Root. That duplication is the single biggest reason
  Criteria pages grow long.
- **Subtle trap:** `.get("name")` is still a string. Without the JPA
  metamodel (extra build setup), Criteria is no safer than JPQL for
  field-rename refactors.

### `query/QuerydslOrderSearch.java`

```java
QOrder o = QOrder.order;
BooleanBuilder where = new BooleanBuilder();
c.country().ifPresent(v -> where.and(cust.country.eq(v)));
JPAQuery<OrderSummary> q = qf.select(Projections.constructor(OrderSummary.class, ...))
    .from(o).join(o.customer, cust).where(where);
```

- **Win:** fully type-safe — rename a field and the compiler points at
  every break. The dynamic-filter pattern (`BooleanBuilder` + a list of
  `where.and(...)` calls) reads like the matrix of "if filter present,
  AND in". The same factory builds the SELECT and the COUNT without
  rebuilding the join tree.
- **Loss:** APT plumbing (Q-classes generated at compile time), extra
  dependency, one more thing to onboard.
- **Default choice for new projects in this repo.**

### `query/NativeOrderSearch.java`

```java
String sql = """
    SELECT o.id, c.name, o.status, o.amount,
           (SELECT COUNT(*) FROM order_items oi WHERE oi.order_id = o.id),
           o.created_at
      FROM orders o JOIN customers c ON c.id = o.customer_id
    """ + where + buildOrderBy(sort) + " LIMIT :pageSize OFFSET :pageOffset";
```

- **Win:** write the SQL you want. Postgres CTEs / LATERAL / window
  functions / GIN-trigram ops just work. The DBA pastes the query into
  psql, no translation.
- **Loss:** zero protection against renamed columns. Result-set mapping
  is positional (`Object[]` indices) unless you commit to
  `@SqlResultSetMapping`. Dynamic WHERE is back to StringBuilder.
- **Reach for it when:** the query genuinely needs DB-specific features,
  or when the DBA owns the SQL and JPA is just the conveyance.

## House rule we land on

| Situation                                              | Use         |
|--------------------------------------------------------|-------------|
| New dynamic-filter list / search screen                | **QueryDSL**|
| Mostly-static query, want SQL in plain sight           | **JPQL**    |
| Spec-only constraint, can't add QueryDSL               | **Criteria**|
| Postgres-specific feature (CTE, JSONB, LATERAL, etc.)  | **Native**  |

## Files

```
src/main/java/com/claude/dbpoc/m09/
├── Application.java               # @SpringBootApplication + JPAQueryFactory @Bean
├── domain/
│   ├── Customer.java
│   ├── Order.java                 # Status enum is here
│   ├── OrderItem.java
│   └── OrderSummary.java          # the DTO every impl returns
├── query/
│   ├── OrderSearch.java           # the contract
│   ├── OrderSearchCriteria.java   # 7 Optional<T> fields
│   ├── JpqlOrderSearch.java
│   ├── CriteriaOrderSearch.java
│   ├── QuerydslOrderSearch.java
│   └── NativeOrderSearch.java
├── repo/                          # Spring Data repos used only by SeedController
└── web/
    ├── SeedController.java        # POST /seed
    └── CompareController.java     # /search/{impl}, /compare
```

## Caveats

- **Microbenchmark trap.** `elapsedMs` here measures the round-trip including
  Hibernate's compile-cache, the JIT, and PG's plan cache. The first call
  to any impl is always slower; we issue a warm-up call before the bench
  to take that out, but treat the numbers as **relative**, not absolute.
- **The point isn't speed.** All four produce structurally identical SQL
  on this query. The point is the *developer-facing* difference: lines
  of code, refactor risk, and the failure mode when you misuse the
  builder.
- **Metamodel for Criteria.** If you actually go Criteria-heavy in
  production, add the Hibernate JPA metamodel generator (a second APT
  processor) and replace `.get("name")` with `Customer_.name`. That
  closes the type-safety gap with QueryDSL — at the price of adopting
  two code generators.
