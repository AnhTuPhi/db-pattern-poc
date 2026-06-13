package com.claude.dbpoc.m27.service;

import com.claude.dbpoc.m27.domain.Order;
import com.claude.dbpoc.m27.repo.OrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The COMMAND side.
 *
 * Every state change runs in ONE transaction that:
 *   (a) modifies the normalized write model (orders table), AND
 *   (b) appends an event to the outbox table.
 *
 * Because both writes are in the same Postgres transaction, they're
 * atomic. There is no "wrote the order but the event didn't publish"
 * outcome. This is the entire point of the outbox pattern: it
 * eliminates the dual-write inconsistency that plagues "write to DB
 * then publish to Kafka" flows.
 */
@Service
public class WriteSideService {

    private final OrderRepository orders;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WriteSideService(OrderRepository orders, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.orders = orders;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public Map<String, Object> placeOrder(Long userId, BigDecimal amount) {
        Order o = orders.save(new Order(userId, amount, "PLACED"));
        appendEvent(o.getId(), "OrderPlaced", mapper.createObjectNode()
            .put("orderId", o.getId())
            .put("userId", userId)
            .put("total", amount)
            .put("status", "PLACED"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", o.getId());
        out.put("status", "PLACED");
        out.put("note",
            "The orders row AND the outbox_events row were written in the SAME tx. " +
            "If the tx rolls back, both disappear together. No partial state. " +
            "The poller will pick the event up within the next poll interval and " +
            "project it into user_order_summary.");
        return out;
    }

    @Transactional
    public Map<String, Object> cancelOrder(Long orderId) {
        Order o = orders.findById(orderId).orElseThrow();
        o.setStatus("CANCELLED");
        // No need to flush — JPA will at commit. But the outbox row
        // is via JdbcTemplate, so we make sure the event has the
        // committed state (status=CANCELLED).
        appendEvent(o.getId(), "OrderCancelled", mapper.createObjectNode()
            .put("orderId", o.getId())
            .put("userId", o.getUserId())
            .put("total", o.getTotal())
            .put("status", "CANCELLED"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("status", "CANCELLED");
        return out;
    }

    private void appendEvent(Long aggregateId, String eventType, ObjectNode payload) {
        try {
            jdbc.update(
                "insert into outbox_events(aggregate_id, event_type, payload) " +
                "values (?, ?, ?::jsonb)",
                aggregateId, eventType, mapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException("outbox append failed: " + e.getMessage(), e);
        }
    }
}
