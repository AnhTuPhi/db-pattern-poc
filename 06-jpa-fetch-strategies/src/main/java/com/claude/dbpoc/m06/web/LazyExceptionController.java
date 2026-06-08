package com.claude.dbpoc.m06.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.hibernate.LazyInitializationException;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.claude.dbpoc.common.SqlCounter;
import com.claude.dbpoc.m06.domain.Order;
import com.claude.dbpoc.m06.dto.OrderSummaryDto;

import lombok.RequiredArgsConstructor;

/**
 * The LazyInitializationException ("LIE") story, told as four endpoints:
 *
 *   /lie/outside-tx       — reproduces the exception (OSIV off, default)
 *   /lie/with-osiv        — same code, OSIV ON profile: works, but at a cost
 *   /lie/with-join-fetch  — correct fix #1: load the graph inside the tx
 *   /lie/with-dto         — correct fix #2: project to a DTO (no proxies)
 *
 * Read these in order. The progression is the whole module.
 */
@RestController
@RequestMapping("/lie")
@RequiredArgsConstructor
public class LazyExceptionController {

    private final OrderLoaderService loader;
    private final SqlCounter sqlCounter;
    private final Environment env;

    @PersistenceContext
    private EntityManager em;

    /**
     * GET /lie/outside-tx
     *
     * The classic LIE reproduction:
     *
     *   1. A @Transactional service method fetches an Order (no JOIN FETCH).
     *   2. The method returns. Transaction commits, Hibernate Session closes.
     *   3. The CONTROLLER (running OUTSIDE the tx) tries to read order.getItems().
     *   4. The collection is a lazy proxy — with no live session, it throws
     *      org.hibernate.LazyInitializationException.
     *
     * We catch and report the exception so the demo doesn't 500. This proves
     * the failure mode — DO NOT consider it a bug to fix here; the entire
     * point is to SHOW it.
     *
     * NOTE: if you run with --spring.profiles.active=osiv-on, this will NOT
     * throw — the session is kept open across the request. Compare the SQL
     * counts and you'll see why that's not actually a fix.
     */
    @GetMapping("/outside-tx")
    public Map<String, Object> outsideTx() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean osivOn = isOsivProfile();
        out.put("profile_osiv_enabled", osivOn);

        sqlCounter.reset();
        Order order = loader.loadOneOrder();           // session closes here
        out.put("statements_inside_tx", sqlCounter.getStatementCount());

        // Now we're outside the @Transactional boundary. Touching a lazy
        // association either throws LIE (OSIV off) or silently opens a new
        // session and runs a follow-up SELECT (OSIV on).
        sqlCounter.reset();
        try {
            int itemCount = order.getItems().size();
            out.put("items", itemCount);
            out.put("statements_in_view", sqlCounter.getStatementCount());
            out.put("outcome",
                osivOn
                    ? "OSIV is ON — Hibernate silently opened the session and ran a SELECT here. " +
                      "No LIE, but the cost is hidden in the view layer."
                    : "No LIE thrown — but you probably ran a previous demo that already initialised this proxy. " +
                      "Restart the app and call /lie/outside-tx as the first endpoint to reproduce.");
        } catch (LazyInitializationException lie) {
            out.put("thrown", "LazyInitializationException");
            out.put("message", lie.getMessage());
            out.put("statements_in_view", sqlCounter.getStatementCount());
            out.put("outcome",
                "OSIV is OFF (the correct default). The proxy refused to initialise outside the tx. " +
                "Real fixes: /lie/with-join-fetch or /lie/with-dto.");
        }
        return out;
    }

    /**
     * GET /lie/with-osiv
     *
     * Same code as /outside-tx, but with a note to remind the reader to
     * run with --spring.profiles.active=osiv-on. The "fix" works — but
     * each lazy getter in the view layer becomes a fresh round-trip.
     */
    @GetMapping("/with-osiv")
    public Map<String, Object> withOsiv() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean osivOn = isOsivProfile();
        out.put("profile_osiv_enabled", osivOn);
        if (!osivOn) {
            out.put("hint",
                "Restart with --spring.profiles.active=osiv-on to see this endpoint succeed. " +
                "With OSIV off, this code path is identical to /lie/outside-tx (it throws LIE).");
        }

        sqlCounter.reset();
        Order order = loader.loadOneOrder();
        long stmtsInsideTx = sqlCounter.getStatementCount();

        sqlCounter.reset();
        try {
            // Each of these getters can issue its own SELECT when OSIV is on.
            int itemCount = order.getItems().size();
            String customerName = order.getCustomer().getName();
            int addressCount = order.getCustomer().getAddresses().size();
            out.put("items", itemCount);
            out.put("customerName", customerName);
            out.put("addresses", addressCount);
            out.put("statements_inside_tx", stmtsInsideTx);
            out.put("statements_in_view", sqlCounter.getStatementCount());
            out.put("cost_of_osiv",
                "Look at statements_in_view — each lazy getter you touched in the view layer " +
                "ran a separate SELECT. You traded a clean exception for hidden N+1s.");
        } catch (LazyInitializationException lie) {
            out.put("thrown", "LazyInitializationException");
            out.put("message", lie.getMessage());
            out.put("hint", "OSIV is off — run with --spring.profiles.active=osiv-on.");
        }
        return out;
    }

    /**
     * GET /lie/with-join-fetch
     *
     * The correct fix #1: tell the query exactly which associations are
     * needed for THIS use case. The graph is fully initialised before the
     * session closes, so iterating items in the view layer is safe and
     * costs zero extra SELECTs.
     */
    @GetMapping("/with-join-fetch")
    public Map<String, Object> withJoinFetch() {
        Map<String, Object> out = new LinkedHashMap<>();

        sqlCounter.reset();
        Order order = loader.loadOneOrderWithItems();   // LEFT JOIN FETCH inside
        long stmtsInsideTx = sqlCounter.getStatementCount();

        sqlCounter.reset();
        // Safe: items collection is already initialised. Zero new SELECTs.
        int itemCount = order.getItems().size();

        out.put("items", itemCount);
        out.put("statements_inside_tx", stmtsInsideTx);
        out.put("statements_in_view", sqlCounter.getStatementCount());
        out.put("note",
            "Zero statements in the view because LEFT JOIN FETCH initialised the collection " +
            "while the session was still open. This is the right fix when you need the entity graph.");
        return out;
    }

    /**
     * GET /lie/with-dto
     *
     * The correct fix #2: don't return an entity at all. The DTO carries
     * exactly the data the endpoint needs. There are no proxies, so a
     * LazyInitializationException isn't even possible — the bug is
     * structurally impossible by construction.
     */
    @GetMapping("/with-dto")
    public Map<String, Object> withDto() {
        Map<String, Object> out = new LinkedHashMap<>();

        sqlCounter.reset();
        List<OrderSummaryDto> rows = loader.loadAllAsDto();
        long stmtsInsideTx = sqlCounter.getStatementCount();

        sqlCounter.reset();
        // Iterate freely outside the tx — these are records, no proxies.
        OrderSummaryDto first = rows.isEmpty() ? null : rows.get(0);

        out.put("rows", rows.size());
        out.put("first", first);
        out.put("statements_inside_tx", stmtsInsideTx);
        out.put("statements_in_view", sqlCounter.getStatementCount());
        out.put("note",
            "Zero statements in the view AND zero risk of LIE — DTOs aren't proxies. " +
            "This is the right default for read paths that go straight to JSON.");
        return out;
    }

    private boolean isOsivProfile() {
        for (String p : env.getActiveProfiles()) {
            if ("osiv-on".equals(p)) return true;
        }
        return false;
    }
}
