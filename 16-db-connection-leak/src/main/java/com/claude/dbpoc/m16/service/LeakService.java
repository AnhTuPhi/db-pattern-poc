package com.claude.dbpoc.m16.service;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Three demos:
 *
 *   1. leak        — borrow N connections and NEVER close them. Track each
 *                    so we can free them after.
 *   2. stats       — snapshot HikariPoolMXBean — active, idle, waiting,
 *                    total. This is the ONLY honest way to know pool state.
 *   3. recover     — close the leaked connections we tracked; pool returns
 *                    to baseline.
 *
 * The point of tracking the "leaks" in a Map is purely so we can clean
 * them up at the end of the demo. In a real leak, the references are lost
 * and the only recovery is restart (or evictNow if you can prove which
 * connection is bad — usually impossible).
 *
 * leakDetectionThreshold (configured in application.yml) is what produces
 * the actual "Apparent connection leak detected" warning with a STACK
 * TRACE of where it was borrowed. That stack trace is the prize.
 */
@Service
public class LeakService {

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    /** Track "leaked" handles so the recover endpoint can close them. */
    private final Map<Long, Connection> leaked = new ConcurrentHashMap<>();
    private final AtomicLong leakId = new AtomicLong();

    public LeakService(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------------
    // 1. LEAK n connections.
    //
    // We borrow `count` connections from the pool, run ONE query so Hikari
    // counts them as "active", then DELIBERATELY never close them. After
    // leakDetectionThreshold ms, Hikari logs:
    //
    //   WARN  c.z.h.pool.ProxyLeakTask - Connection leak detection triggered
    //         for connection ..., stack trace follows:
    //         java.lang.Exception
    //             at com.claude.dbpoc.m16.service.LeakService.leak(...)
    //
    // The stack trace is the entire point — it identifies the code path
    // that borrowed the connection and forgot it.
    // ---------------------------------------------------------------------
    public Map<String, Object> leak(int count) {
        List<Long> ids = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            try {
                Connection c = dataSource.getConnection();
                c.createStatement().execute("select 1");
                long id = leakId.incrementAndGet();
                leaked.put(id, c);
                ids.add(id);
            } catch (SQLException e) {
                failures.add(e.getMessage());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", "borrow " + count + " connections, never close");
        out.put("leakedHandles", ids);
        out.put("failedToBorrow", failures.size());
        out.put("failuresFirstFew", failures.size() > 3 ? failures.subList(0, 3) : failures);
        out.put("totalCurrentlyLeaked", leaked.size());
        out.put("note",
            "Watch the app logs — after leakDetectionThreshold ms HikariCP will " +
            "log a stack trace pointing at THIS method. That stack is how you find " +
            "the leak in real code. Now poll /leak/stats to watch active climb and " +
            "idle drop. When you're done, hit /leak/recover.");
        out.putAll(snapshot());
        return out;
    }

    // ---------------------------------------------------------------------
    // 2. POOL STATS.
    //
    // The ONLY honest source for pool state. activeConnections is the
    // "borrowed but not returned" count — that's your leak gauge. Wire
    // it to Prometheus in production. If active stays at maximumPoolSize
    // for minutes with no real traffic, you have a leak.
    // ---------------------------------------------------------------------
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", "live pool snapshot");
        out.putAll(snapshot());
        out.put("note",
            "active = borrowed (in use OR leaked). idle = in pool ready to lend. " +
            "waiting = threads blocked in dataSource.getConnection(). total = active + idle. " +
            "A healthy idle pool: active fluctuates with traffic, idle stays >= minimumIdle, " +
            "waiting is ~0. A leaking pool: active stair-steps UP and never comes back down.");
        return out;
    }

    private Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (dataSource instanceof HikariDataSource ds) {
            HikariPoolMXBean mx = ds.getHikariPoolMXBean();
            m.put("active", mx.getActiveConnections());
            m.put("idle", mx.getIdleConnections());
            m.put("waiting", mx.getThreadsAwaitingConnection());
            m.put("total", mx.getTotalConnections());
            m.put("maximumPoolSize", ds.getMaximumPoolSize());
            m.put("minimumIdle", ds.getMinimumIdle());
            m.put("leakDetectionThresholdMs", ds.getLeakDetectionThreshold());
        }
        return m;
    }

    // ---------------------------------------------------------------------
    // 3. RECOVER (for the demo only).
    //
    // Close every connection we tracked in the leak demo. In real code,
    // you almost never have the leaked Connection references — they were
    // dropped on the floor. The only recovery is process restart, OR if
    // you've identified them via Postgres-side queries (which connection
    // pid is doing what), you can `pg_terminate_backend(pid)` server-side.
    // ---------------------------------------------------------------------
    public Map<String, Object> recover() {
        int closed = 0;
        int errors = 0;
        for (Connection c : leaked.values()) {
            try { c.close(); closed++; }
            catch (SQLException e) { errors++; }
        }
        leaked.clear();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", "close every tracked-leak connection");
        out.put("closed", closed);
        out.put("errors", errors);
        out.putAll(snapshot());
        out.put("note",
            "In a REAL leak you don't have the references. The only fixes are: " +
            "(a) deploy the patch that closes properly, (b) restart, " +
            "(c) pg_terminate_backend the offending pids from psql. " +
            "Watch /leak/stats — active should now be back to ~0.");
        return out;
    }
}
