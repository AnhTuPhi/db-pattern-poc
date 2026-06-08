package com.claude.dbpoc.m05.controller;

import com.claude.dbpoc.m05.domain.Item;
import com.claude.dbpoc.m05.domain.Order;
import com.claude.dbpoc.m05.repo.OrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Builds the N orders × M items dataset that the /demo and /compare endpoints
 * read against. Idempotent in spirit: wipes the tables first so re-running
 * /seed always gives you the requested shape exactly.
 */
@RestController
@RequestMapping("/seed")
public class SeedController {

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private OrderRepository orderRepo;

    /**
     * POST /seed?orders=100&itemsPerOrder=5
     *
     * Wrapped in a single transaction so the inserts batch up. With
     * spring.jpa.properties.hibernate.jdbc.batch_size=50 the seed becomes
     * a handful of multi-row INSERTs instead of N*M round-trips.
     */
    @PostMapping
    @Transactional
    public Map<String, Object> seed(@RequestParam(defaultValue = "100") int orders,
                                    @RequestParam(defaultValue = "5") int itemsPerOrder) {
        // Wipe in child→parent order. Native SQL with TRUNCATE CASCADE is
        // faster than orderRepo.deleteAll() because it skips entity loading.
        em.createNativeQuery("TRUNCATE TABLE items, orders RESTART IDENTITY CASCADE").executeUpdate();

        Instant now = Instant.now();
        for (int i = 0; i < orders; i++) {
            Order o = new Order();
            o.setCustomerName("customer-" + i);
            o.setCreatedAt(now);
            for (int j = 0; j < itemsPerOrder; j++) {
                Item it = new Item();
                it.setProductName("product-" + j);
                it.setQuantity(1 + (j % 5));
                it.setUnitPrice(9.99 + j);
                o.addItem(it);
            }
            em.persist(o);
            // Flush + clear in chunks to keep the persistence context bounded
            // and trigger JDBC batching at well-defined boundaries.
            if (i % 50 == 0 && i > 0) {
                em.flush();
                em.clear();
            }
        }
        em.flush();

        return Map.of(
            "orders", orders,
            "itemsPerOrder", itemsPerOrder,
            "totalItems", (long) orders * itemsPerOrder,
            "message", "seeded; now hit /compare/all?orders=" + orders);
    }
}
