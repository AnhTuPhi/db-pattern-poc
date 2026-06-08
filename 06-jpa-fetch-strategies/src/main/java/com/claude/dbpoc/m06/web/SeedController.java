package com.claude.dbpoc.m06.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.claude.dbpoc.m06.domain.Address;
import com.claude.dbpoc.m06.domain.Customer;
import com.claude.dbpoc.m06.domain.Order;
import com.claude.dbpoc.m06.domain.OrderItem;
import com.claude.dbpoc.m06.repo.CustomerRepository;

import lombok.RequiredArgsConstructor;

/**
 * POST /seed?customers=N&ordersPerCustomer=M&itemsPerOrder=K
 *
 * Wipes and repopulates the m06 schema with a small synthetic graph. The
 * defaults (5 / 3 / 4 = 5 customers x 3 orders x 4 items = 60 items) are
 * deliberately tiny so the SQL counts in the demo endpoints stay readable.
 */
@RestController
@RequestMapping("/seed")
@RequiredArgsConstructor
public class SeedController {

    private final CustomerRepository customerRepo;

    @PostMapping
    @Transactional
    public Map<String, Object> seed(@RequestParam(defaultValue = "5") int customers,
                                    @RequestParam(defaultValue = "3") int ordersPerCustomer,
                                    @RequestParam(defaultValue = "4") int itemsPerOrder) {

        // delete-all relies on cascade=ALL + orphanRemoval on Customer.
        customerRepo.deleteAllInBatch();

        Instant now = Instant.now();
        int totalOrders = 0;
        int totalItems = 0;

        for (int c = 0; c < customers; c++) {
            Customer customer = new Customer("Customer " + (c + 1));

            // Two addresses each — used implicitly by some demos.
            customer.getAddresses().add(new Address(customer, c + " Main St", "City " + c));
            customer.getAddresses().add(new Address(customer, c + " Side St", "City " + c));

            for (int o = 0; o < ordersPerCustomer; o++) {
                BigDecimal orderTotal = BigDecimal.ZERO;
                Order order = new Order(customer, now.minus(o, ChronoUnit.DAYS), BigDecimal.ZERO);

                for (int i = 0; i < itemsPerOrder; i++) {
                    BigDecimal price = BigDecimal.valueOf(10 + i);
                    int qty = 1 + (i % 3);
                    order.getItems().add(new OrderItem(order, "SKU-" + c + "-" + o + "-" + i, qty, price));
                    orderTotal = orderTotal.add(price.multiply(BigDecimal.valueOf(qty)));
                    totalItems++;
                }
                order.setTotal(orderTotal);
                customer.getOrders().add(order);
                totalOrders++;
            }
            customerRepo.save(customer);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("customers", customers);
        out.put("orders", totalOrders);
        out.put("items", totalItems);
        out.put("note", "Tiny graph by design — keeps the SQL counts in /eager-trap readable.");
        return out;
    }
}
