package com.claude.dbpoc.m08;

import com.claude.dbpoc.common.SqlCounter;
import com.claude.dbpoc.m08.domain.AssignedCustomer;
import com.claude.dbpoc.m08.domain.CustomerOrder;
import com.claude.dbpoc.m08.domain.IdentityCustomer;
import com.claude.dbpoc.m08.domain.SequenceCustomer;
import com.claude.dbpoc.m08.domain.SequenceCustomer100;
import com.claude.dbpoc.m08.repo.AssignedCustomerRepository;
import com.claude.dbpoc.m08.repo.CustomerOrderRepository;
import com.claude.dbpoc.m08.repo.IdentityCustomerRepository;
import com.claude.dbpoc.m08.repo.SequenceCustomer100Repository;
import com.claude.dbpoc.m08.repo.SequenceCustomerRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The actual benchmark variants. Each method:
 *   1) resets the SqlCounter
 *   2) inserts N rows the variant's way
 *   3) returns a result map with elapsed ms + statement count + batch count
 *
 * Every method is @Transactional so the persistence context, flush, and
 * batch boundaries are real. Each variant runs in its own transaction so
 * timings don't bleed.
 */
@Service
public class BenchService {

    private final IdentityCustomerRepository identityRepo;
    private final SequenceCustomerRepository sequenceRepo;
    private final SequenceCustomer100Repository sequence100Repo;
    private final AssignedCustomerRepository assignedRepo;
    private final CustomerOrderRepository customerOrderRepo;
    private final JdbcTemplate jdbc;
    private final SqlCounter sqlCounter;

    @PersistenceContext
    private EntityManager em;

    public BenchService(IdentityCustomerRepository identityRepo,
                        SequenceCustomerRepository sequenceRepo,
                        SequenceCustomer100Repository sequence100Repo,
                        AssignedCustomerRepository assignedRepo,
                        CustomerOrderRepository customerOrderRepo,
                        JdbcTemplate jdbc,
                        SqlCounter sqlCounter) {
        this.identityRepo = identityRepo;
        this.sequenceRepo = sequenceRepo;
        this.sequence100Repo = sequence100Repo;
        this.assignedRepo = assignedRepo;
        this.customerOrderRepo = customerOrderRepo;
        this.jdbc = jdbc;
        this.sqlCounter = sqlCounter;
    }

    // ---------------------------------------------------------------------
    // The baseline. JdbcTemplate.batchUpdate is what the JPA variants are
    // trying to approximate. Nothing magic — one PreparedStatement, N
    // addBatch() calls, executeBatch(). This is the floor of what the
    // network and DB can do.
    // ---------------------------------------------------------------------
    @Transactional
    public Map<String, Object> jdbcBaseline(int n) {
        sqlCounter.reset();
        long t0 = System.nanoTime();

        List<Object[]> args = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            args.add(new Object[]{
                "jdbc-" + i, "jdbc" + i + "@example.com", "US",
                Timestamp.from(Instant.now()), new BigDecimal("100.00")
            });
        }
        jdbc.batchUpdate(
            "INSERT INTO assigned_customer (id, name, email, country, created_at, balance) " +
            "VALUES (gen_random_uuid()::text, ?, ?, ?, ?, ?)",
            args
        );

        long elapsed = System.nanoTime() - t0;
        return result("jdbc-baseline", n, elapsed,
            "JdbcTemplate.batchUpdate — the floor. No persistence context, no dirty-check, " +
            "no flush. Every JPA variant below is judged against this.");
    }

    // ---------------------------------------------------------------------
    // The anti-pattern. IDENTITY disables Hibernate's insert batching.
    // Expect N statements, no batches, slow elapsed.
    // ---------------------------------------------------------------------
    @Transactional
    public Map<String, Object> identityVariant(int n) {
        sqlCounter.reset();
        long t0 = System.nanoTime();

        List<IdentityCustomer> batch = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            batch.add(IdentityCustomer.builder()
                .name("id-" + i)
                .email("id" + i + "@example.com")
                .country("US")
                .createdAt(Instant.now())
                .balance(new BigDecimal("100.00"))
                .build());
        }
        identityRepo.saveAll(batch);
        em.flush();

        long elapsed = System.nanoTime() - t0;
        return result("identity", n, elapsed,
            "GenerationType.IDENTITY forces getGeneratedKeys() per row. Hibernate logs " +
            "'HHH000069: disabling insert batching' at startup. You should see ~N statements.");
    }

    // ---------------------------------------------------------------------
    // SEQUENCE allocationSize=50 + batch_size=50. The win.
    // Expect: 1 sequence call per batch + 1 batch of 50 INSERTs.
    // ---------------------------------------------------------------------
    @Transactional
    public Map<String, Object> sequenceVariant(int n) {
        sqlCounter.reset();
        long t0 = System.nanoTime();

        // saveAll() keeps everything in the PC. For 10k+ rows we periodically
        // clear to keep dirty-check cheap (see /bench tip).
        int flushEvery = 50;
        for (int i = 0; i < n; i++) {
            sequenceRepo.save(SequenceCustomer.builder()
                .name("seq-" + i)
                .email("seq" + i + "@example.com")
                .country("US")
                .createdAt(Instant.now())
                .balance(new BigDecimal("100.00"))
                .build());
            if (i > 0 && i % flushEvery == 0) {
                em.flush();
                em.clear();
            }
        }
        em.flush();

        long elapsed = System.nanoTime() - t0;
        return result("sequence-batch50", n, elapsed,
            "GenerationType.SEQUENCE with allocationSize=50 + jdbc.batch_size=50. The " +
            "winning combo: one sequence hop per 50 inserts, one batched INSERT per 50.");
    }

    // ---------------------------------------------------------------------
    // Sequence allocationSize=100 — fewer sequence hops, same batch size.
    // Trade-off: id gaps when a JVM dies mid-allocation. Most apps don't care.
    // ---------------------------------------------------------------------
    @Transactional
    public Map<String, Object> sequence100Variant(int n) {
        sqlCounter.reset();
        long t0 = System.nanoTime();

        int flushEvery = 50;
        for (int i = 0; i < n; i++) {
            sequence100Repo.save(SequenceCustomer100.builder()
                .name("seq100-" + i)
                .email("seq100" + i + "@example.com")
                .country("US")
                .createdAt(Instant.now())
                .balance(new BigDecimal("100.00"))
                .build());
            if (i > 0 && i % flushEvery == 0) {
                em.flush();
                em.clear();
            }
        }
        em.flush();

        long elapsed = System.nanoTime() - t0;
        return result("sequence-batch50-alloc100", n, elapsed,
            "allocationSize=100 halves sequence round-trips vs alloc=50. On a fast LAN " +
            "the delta is small; on Oracle RAC or a high-latency link it's measurable.");
    }

    // ---------------------------------------------------------------------
    // Application-assigned UUID. The "DB-agnostic" winner.
    // No IDENTITY, no sequence, batching just works.
    // ---------------------------------------------------------------------
    @Transactional
    public Map<String, Object> assignedVariant(int n) {
        sqlCounter.reset();
        long t0 = System.nanoTime();

        int flushEvery = 50;
        for (int i = 0; i < n; i++) {
            assignedRepo.save(AssignedCustomer.builder()
                .id(AssignedCustomer.newId())
                .name("uuid-" + i)
                .email("uuid" + i + "@example.com")
                .country("US")
                .createdAt(Instant.now())
                .balance(new BigDecimal("100.00"))
                .build());
            if (i > 0 && i % flushEvery == 0) {
                em.flush();
                em.clear();
            }
        }
        em.flush();

        long elapsed = System.nanoTime() - t0;
        return result("assigned-uuid", n, elapsed,
            "Application-assigned UUID PK. No sequence hops, no IDENTITY problem. Best for " +
            "sharded/idempotent writes. PK is 16 bytes vs 8 — index size cost is real.");
    }

    // ---------------------------------------------------------------------
    // SEQUENCE with batching DISABLED (set batch_size=1 effectively by not
    // flushing in chunks AND interleaving entity types so ordering doesn't
    // help). Hibernate sends every insert as its own statement.
    //
    // Compare with sequenceVariant — same entity, batching off.
    // ---------------------------------------------------------------------
    @Transactional
    public Map<String, Object> sequenceUnbatched(int n) {
        sqlCounter.reset();
        long t0 = System.nanoTime();

        // To force per-statement execution we flush after EVERY save.
        for (int i = 0; i < n; i++) {
            sequenceRepo.save(SequenceCustomer.builder()
                .name("seqU-" + i)
                .email("seqU" + i + "@example.com")
                .country("US")
                .createdAt(Instant.now())
                .balance(new BigDecimal("100.00"))
                .build());
            em.flush();
            em.clear();
        }

        long elapsed = System.nanoTime() - t0;
        return result("sequence-unbatched", n, elapsed,
            "Same SEQUENCE entity, but flush-after-each-save defeats batching. Surfaces " +
            "what 'just use sequences' is worth WITHOUT the batch_size knob set.");
    }

    // ---------------------------------------------------------------------
    // Mixed entity types — the order_inserts story.
    // Interleave Customer + Order. Without order_inserts, every type
    // switch flushes the current batch. With it, Hibernate groups types
    // and you get 2 batches instead of 2*N.
    // ---------------------------------------------------------------------
    @Transactional
    public Map<String, Object> orderedInsertsVariant(int n) {
        sqlCounter.reset();
        long t0 = System.nanoTime();

        for (int i = 0; i < n; i++) {
            sequenceRepo.save(SequenceCustomer.builder()
                .name("mix-cust-" + i)
                .email("mixc" + i + "@example.com")
                .country("US")
                .createdAt(Instant.now())
                .balance(new BigDecimal("100.00"))
                .build());
            customerOrderRepo.save(CustomerOrder.builder()
                .customerId((long) i)
                .amount(new BigDecimal("50.00"))
                .placedAt(Instant.now())
                .build());
            if (i > 0 && i % 50 == 0) {
                em.flush();
                em.clear();
            }
        }
        em.flush();

        long elapsed = System.nanoTime() - t0;
        return result("mixed-ordered-inserts", n * 2, elapsed,
            "Interleaved Customer + Order writes. hibernate.order_inserts=true groups by " +
            "entity type at flush time, so two batches per flush window instead of one per " +
            "type-switch. Disable order_inserts and rerun to feel the difference.");
    }

    private Map<String, Object> result(String variant, int rows, long elapsedNanos, String verdict) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("variant", variant);
        out.put("rowsInserted", rows);
        out.put("sqlStatements", sqlCounter.getStatementCount());
        out.put("batches", sqlCounter.getBatchCount());
        out.put("elapsedMs", elapsedNanos / 1_000_000.0);
        out.put("rowsPerSecond", (long) (rows / (elapsedNanos / 1_000_000_000.0)));
        out.put("verdict", verdict);
        return out;
    }
}
