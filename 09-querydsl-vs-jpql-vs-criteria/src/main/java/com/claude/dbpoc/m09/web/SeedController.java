package com.claude.dbpoc.m09.web;

import com.claude.dbpoc.m09.domain.Customer;
import com.claude.dbpoc.m09.domain.Order;
import com.claude.dbpoc.m09.domain.Order.Status;
import com.claude.dbpoc.m09.domain.OrderItem;
import com.claude.dbpoc.m09.repo.CustomerRepository;
import com.claude.dbpoc.m09.repo.OrderItemRepository;
import com.claude.dbpoc.m09.repo.OrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Seeds the customers / orders / order_items tables with enough variety for
 * the dynamic-filter query to actually exercise each filter:
 *
 *   - Customer names span A-Z so the LIKE filter has a non-empty result for
 *     most prefixes.
 *   - Country rotates through 4 codes for the country = filter.
 *   - Status rotates through all 4 enum values for the IN filter.
 *   - Amount + createdAt are randomised within a window so the range
 *     filters are not no-ops.
 *
 * One call: POST /seed?customers=N&ordersPer=N&itemsPer=N.
 */
@RestController
@RequestMapping("/seed")
public class SeedController {

    private final CustomerRepository customerRepo;
    private final OrderRepository orderRepo;
    private final OrderItemRepository orderItemRepo;

    @PersistenceContext
    private EntityManager em;

    public SeedController(CustomerRepository customerRepo,
                          OrderRepository orderRepo,
                          OrderItemRepository orderItemRepo) {
        this.customerRepo = customerRepo;
        this.orderRepo = orderRepo;
        this.orderItemRepo = orderItemRepo;
    }

    @PostMapping
    @Transactional
    public Map<String, Object> seed(
            @RequestParam(defaultValue = "100") int customers,
            @RequestParam(defaultValue = "10") int ordersPer,
            @RequestParam(defaultValue = "3") int itemsPer) {

        // FK order: items -> orders -> customers.
        orderItemRepo.deleteAllInBatch();
        orderRepo.deleteAllInBatch();
        customerRepo.deleteAllInBatch();

        String[] countries = { "US", "GB", "DE", "FR" };
        Status[] statuses = Status.values();
        Random r = new Random(42);  // deterministic for reproducibility

        int orderCount = 0;
        int itemCount = 0;
        Instant now = Instant.now();

        for (int i = 0; i < customers; i++) {
            Customer c = new Customer();
            // Names cycle A0, B0, ... Z0, A1, B1, ... so a LIKE 'a%' filter has hits.
            c.setName(((char) ('A' + (i % 26))) + "-customer-" + i);
            c.setCountry(countries[i % countries.length]);
            c.setVipTier(i % 4);
            customerRepo.save(c);

            for (int o = 0; o < ordersPer; o++) {
                Order order = new Order();
                order.setCustomer(c);
                order.setStatus(statuses[r.nextInt(statuses.length)]);
                order.setAmount(BigDecimal.valueOf(10 + r.nextInt(990)).setScale(2));
                // Spread orders across 30 days so date-range filters bite.
                order.setCreatedAt(now.minus(r.nextInt(30 * 24), ChronoUnit.HOURS));
                orderRepo.save(order);
                orderCount++;

                for (int it = 0; it < itemsPer; it++) {
                    OrderItem item = new OrderItem();
                    item.setOrder(order);
                    item.setProductName("product-" + it);
                    item.setQty(1 + r.nextInt(5));
                    item.setUnitPrice(BigDecimal.valueOf(5 + r.nextInt(95)).setScale(2));
                    orderItemRepo.save(item);
                    itemCount++;
                }
            }

            if (i % 25 == 0) {
                em.flush();
                em.clear();
            }
        }
        em.flush();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("customers", customers);
        out.put("orders", orderCount);
        out.put("orderItems", itemCount);
        out.put("note", "Seed complete. Hit /search/{impl} or /compare with optional filters.");
        return out;
    }
}
