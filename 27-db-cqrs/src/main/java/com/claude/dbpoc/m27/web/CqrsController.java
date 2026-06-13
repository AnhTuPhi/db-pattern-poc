package com.claude.dbpoc.m27.web;

import com.claude.dbpoc.m27.service.OutboxPoller;
import com.claude.dbpoc.m27.service.ReadSideService;
import com.claude.dbpoc.m27.service.WriteSideService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Endpoints (port 8227):
 *
 *   --- WRITE side (commands) ---
 *   POST /cqrs/order?userId=1&amount=99           — places an order; emits OrderPlaced
 *   POST /cqrs/order/{id}/cancel                  — cancels; emits OrderCancelled
 *
 *   --- READ side (queries) ---
 *   GET  /cqrs/summary/{userId}                   — eventually-consistent read model
 *   GET  /cqrs/raw/{userId}                       — same numbers from write model (authoritative)
 *
 *   --- Operational ---
 *   GET  /cqrs/outbox                             — backlog stats
 *   POST /cqrs/rebuild                            — truncate read model + replay outbox
 *   POST /cqrs/poll-now                           — force a poller drain (test/debug)
 */
@RestController
@RequestMapping("/cqrs")
public class CqrsController {

    private final WriteSideService writes;
    private final ReadSideService reads;
    private final OutboxPoller poller;

    public CqrsController(WriteSideService writes, ReadSideService reads, OutboxPoller poller) {
        this.writes = writes;
        this.reads = reads;
        this.poller = poller;
    }

    @PostMapping("/order")
    public Map<String, Object> place(@RequestParam Long userId, @RequestParam BigDecimal amount) {
        return writes.placeOrder(userId, amount);
    }

    @PostMapping("/order/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable Long id) { return writes.cancelOrder(id); }

    @GetMapping("/summary/{userId}")
    public Map<String, Object> summary(@PathVariable Long userId) {
        return reads.getUserSummary(userId);
    }

    @GetMapping("/raw/{userId}")
    public Map<String, Object> raw(@PathVariable Long userId) {
        return reads.getUserSummaryFromWriteModel(userId);
    }

    @GetMapping("/outbox")
    public Map<String, Object> outbox() {
        Map<String, Object> out = new LinkedHashMap<>(reads.outboxBacklog());
        out.put("totalProcessedSinceBoot", poller.getTotalProcessed());
        out.put("lastError", poller.getLastError());
        return out;
    }

    @PostMapping("/rebuild")
    public Map<String, Object> rebuild() { return reads.rebuildReadModel(); }

    @PostMapping("/poll-now")
    public Map<String, Object> pollNow() {
        int n = poller.drainOnce();
        return Map.of("processedThisPass", n);
    }
}
