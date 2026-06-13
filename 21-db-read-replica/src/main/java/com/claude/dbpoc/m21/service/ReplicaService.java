package com.claude.dbpoc.m21.service;

import com.claude.dbpoc.m21.domain.Account;
import com.claude.dbpoc.m21.repo.AccountRepository;
import com.claude.dbpoc.m21.routing.RoutingDataSource;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Four endpoints:
 *
 *   1. write      — writes go to PRIMARY (default @Transactional).
 *   2. read       — reads go to REPLICA (@Transactional(readOnly=true)).
 *   3. lagDemo    — write then immediately read; with lag>0, the read
 *                   sees STALE data. This is "read-your-writes" broken.
 *   4. stickyRead — same race, but uses FORCE_PRIMARY for `windowMs` ms
 *                   after the write — reads correctly.
 *
 * The replica is simulated: we hold a separate `replica.account` table
 * in the same Postgres instance and "replicate" by COPYing from primary
 * with a configurable delay. Real streaming replication has the same
 * shape of lag — milliseconds normally, seconds when WAL is busy.
 */
@Service
public class ReplicaService {

    private final AccountRepository accountRepo;
    private final HikariDataSource primaryDs;
    private final HikariDataSource replicaDs;

    @PersistenceContext
    private EntityManager em;

    public ReplicaService(AccountRepository accountRepo,
                          @Qualifier("primaryDataSource") HikariDataSource primaryDs,
                          @Qualifier("replicaDataSource") HikariDataSource replicaDs) {
        this.accountRepo = accountRepo;
        this.primaryDs = primaryDs;
        this.replicaDs = replicaDs;
    }

    // ---------------------------------------------------------------------
    // 1. WRITE — implicit primary routing.
    // ---------------------------------------------------------------------
    @Transactional
    public Map<String, Object> write(Long id, BigDecimal newBalance) {
        Account a = accountRepo.findById(id).orElseThrow();
        BigDecimal old = a.getBalance();
        a.setBalance(newBalance);
        accountRepo.saveAndFlush(a);
        return Map.of("id", id, "oldBalance", old, "newBalance", newBalance,
            "routedTo", "PRIMARY", "note",
            "Default @Transactional → readOnly=false → RoutingDataSource picked PRIMARY.");
    }

    // ---------------------------------------------------------------------
    // 2. READ — explicit replica routing.
    // ---------------------------------------------------------------------
    @Transactional(readOnly = true)
    public Map<String, Object> read(Long id) {
        Account a = accountRepo.findById(id).orElseThrow();
        return Map.of("id", id, "balance", a.getBalance(),
            "routedTo", "REPLICA", "note",
            "@Transactional(readOnly=true) → RoutingDataSource picked REPLICA. " +
            "Beware: this is your potentially-stale read.");
    }

    // ---------------------------------------------------------------------
    // 3. LAG DEMO — write to primary, "replicate" with delay, read replica.
    //
    // The replica row is updated `lagMs` after the primary commit. If the
    // app reads in between, it sees the OLD value — the classic
    // "I just saved it but the GET doesn't show it" bug.
    // ---------------------------------------------------------------------
    public Map<String, Object> lagDemo(Long id, BigDecimal newBalance, long lagMs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", "write primary, read replica before lag elapses");
        out.put("lagMs", lagMs);

        BigDecimal beforeWrite = directRead(replicaDs, id);

        // Write to primary.
        write(id, newBalance);
        out.put("writeCommittedAt", "t=0ms");

        // Read replica BEFORE the simulated replication catches up.
        BigDecimal replicaImmediate = directRead(replicaDs, id);
        out.put("replicaReadAtT0", replicaImmediate);

        // Simulate replication: copy primary → replica after lagMs.
        try { Thread.sleep(lagMs); } catch (InterruptedException ignored) {}
        replicateNow(id);

        // Read AFTER lag.
        BigDecimal replicaAfter = directRead(replicaDs, id);
        out.put("replicaReadAfterLag", replicaAfter);

        out.put("verdict",
            replicaImmediate.compareTo(newBalance) != 0
                ? "Read-your-writes BROKEN. App wrote " + newBalance +
                  " but immediate replica read returned " + replicaImmediate +
                  " (the pre-write value " + beforeWrite + "). After " + lagMs +
                  "ms the replica caught up to " + replicaAfter +
                  ". This is what real replica lag looks like."
                : "No lag observed — replication was instant in this run.");
        return out;
    }

    // ---------------------------------------------------------------------
    // 4. STICKY READ — primary-fallback for `windowMs` after a write.
    //
    // The fix: after a write, set FORCE_PRIMARY=true on the calling
    // thread for some window. Subsequent reads (even with readOnly=true)
    // are pinned to PRIMARY. Past the window, they go to replica again.
    //
    // This is the simplest correct pattern. Alternatives:
    //   - Track LSN per request; replica wait-for-LSN ("synchronous_commit").
    //   - Read-after-write only on PK of the just-written row.
    //   - Cookie-based stickiness for HTTP sessions ("read-from-primary"
    //     cookie set by the write response, read by the next-request gateway).
    // ---------------------------------------------------------------------
    public Map<String, Object> stickyRead(Long id, BigDecimal newBalance,
                                          long lagMs, long stickyWindowMs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", "sticky-primary read for " + stickyWindowMs + "ms after write");

        write(id, newBalance);
        long stickyUntil = System.currentTimeMillis() + stickyWindowMs;

        // Read DURING the sticky window — pinned to PRIMARY.
        RoutingDataSource.FORCE_PRIMARY.set(System.currentTimeMillis() < stickyUntil);
        BigDecimal readInWindow = directRead(currentTarget() == RoutingDataSource.Target.PRIMARY ? primaryDs : replicaDs, id);
        out.put("readDuringStickyWindow", readInWindow);
        out.put("routedTo_duringWindow", currentTarget().toString());

        // Simulate the lag period; meanwhile FORCE_PRIMARY stays true until window elapses.
        try { Thread.sleep(Math.min(stickyWindowMs, lagMs)); } catch (InterruptedException ignored) {}
        replicateNow(id);
        try { Thread.sleep(Math.max(0, stickyWindowMs - lagMs)); } catch (InterruptedException ignored) {}

        // Past the window — go to replica.
        RoutingDataSource.FORCE_PRIMARY.remove();
        BigDecimal readAfterWindow = directRead(replicaDs, id);
        out.put("readAfterStickyWindow", readAfterWindow);
        out.put("routedTo_afterWindow", "REPLICA");
        out.put("verdict",
            readInWindow.compareTo(newBalance) == 0
                ? "Sticky-primary read returned the just-written value. " +
                  "Replica caught up before the window closed; subsequent reads " +
                  "use the replica safely. This is the production pattern."
                : "Sticky window failed — investigate FORCE_PRIMARY plumbing.");
        return out;
    }

    /**
     * Seed identical rows on primary and replica. Bypasses the routing
     * proxy so we don't end up "writing to the replica" accidentally.
     */
    @Transactional(propagation = Propagation.NEVER)
    public Map<String, Object> seed() {
        truncate(primaryDs);
        truncate(replicaDs);
        Long id = insert(primaryDs, "alice", new BigDecimal("1000.00"));
        insert(replicaDs, "alice", new BigDecimal("1000.00")); // mirror the same id
        return Map.of("aliceId", id,
            "primaryBalance", new BigDecimal("1000.00"),
            "replicaBalance", new BigDecimal("1000.00"));
    }

    // ---- helpers --------------------------------------------------------

    private RoutingDataSource.Target currentTarget() {
        return Boolean.TRUE.equals(RoutingDataSource.FORCE_PRIMARY.get())
            ? RoutingDataSource.Target.PRIMARY
            : RoutingDataSource.Target.REPLICA;
    }

    /** Read directly from a specific DS, bypassing routing. */
    private BigDecimal directRead(HikariDataSource ds, Long id) {
        try (var c = ds.getConnection();
             var ps = c.prepareStatement("select balance from account where id = ?")) {
            ps.setLong(1, id);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return rs.getBigDecimal(1);
            }
        } catch (Exception e) {
            throw new RuntimeException("direct read failed: " + e.getMessage(), e);
        }
    }

    /** Copy primary's row → replica. Stands in for streaming replication. */
    private void replicateNow(Long id) {
        BigDecimal v = directRead(primaryDs, id);
        try (var c = replicaDs.getConnection();
             var ps = c.prepareStatement("update account set balance = ? where id = ?")) {
            ps.setBigDecimal(1, v);
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("replicate failed: " + e.getMessage(), e);
        }
    }

    private void truncate(HikariDataSource ds) {
        try (var c = ds.getConnection(); var s = c.createStatement()) {
            s.execute("truncate account restart identity");
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private Long insert(HikariDataSource ds, String owner, BigDecimal bal) {
        try (var c = ds.getConnection();
             var ps = c.prepareStatement("insert into account(owner, balance) values (?,?) returning id")) {
            ps.setString(1, owner);
            ps.setBigDecimal(2, bal);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
