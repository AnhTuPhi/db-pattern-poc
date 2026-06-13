package com.claude.dbpoc.m23.web;

import com.claude.dbpoc.m23.routing.ShardRouter;
import com.claude.dbpoc.m23.service.ShardingService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Endpoints (port 8223):
 *
 *   POST /shard/seed?tenants=200&ordersPerTenant=10
 *   GET  /shard/tenant/{id}                       — single-shard read
 *   GET  /shard/scatter-gather                    — cross-shard aggregate
 *   GET  /shard/distribution?sample=10000
 *   GET  /shard/reshard-simulation?sample=10000   — ch vs modulo movement
 *   POST /shard/dual-write?tenantId=1&amount=99&oldShard=s0&newShard=s2
 *
 *   POST /shard/strategy?value=CONSISTENT_HASH    — switch router strategy
 *   GET  /shard/strategy                          — read current strategy
 *   POST /shard/setup-empty                       — create empty orders on every shard
 */
@RestController
@RequestMapping("/shard")
public class ShardingController {

    private final ShardingService svc;
    private final ShardRouter router;

    public ShardingController(ShardingService svc, ShardRouter router) {
        this.svc = svc;
        this.router = router;
    }

    @PostMapping("/seed")
    public Map<String, Object> seed(@RequestParam(defaultValue = "200") int tenants,
                                    @RequestParam(defaultValue = "10") int ordersPerTenant) {
        return svc.seed(tenants, ordersPerTenant);
    }

    @GetMapping("/tenant/{id}")
    public Map<String, Object> tenant(@PathVariable long id) { return svc.getForTenant(id); }

    @GetMapping("/scatter-gather")
    public Map<String, Object> scatterGather() { return svc.scatterGather(); }

    @GetMapping("/distribution")
    public Map<String, Object> distribution(@RequestParam(defaultValue = "10000") int sample) {
        return svc.distribution(sample);
    }

    @GetMapping("/reshard-simulation")
    public Map<String, Object> resharding(@RequestParam(defaultValue = "10000") int sample) {
        return svc.reshardSimulation(sample);
    }

    @PostMapping("/dual-write")
    public Map<String, Object> dualWrite(@RequestParam long tenantId,
                                         @RequestParam BigDecimal amount,
                                         @RequestParam String oldShard,
                                         @RequestParam String newShard) {
        return svc.dualWrite(tenantId, amount, oldShard, newShard);
    }

    @PostMapping("/strategy")
    public Map<String, Object> setStrategy(@RequestParam ShardRouter.Strategy value) {
        router.setStrategy(value);
        return Map.of("strategy", router.getStrategy().toString());
    }

    @GetMapping("/strategy")
    public Map<String, Object> getStrategy() {
        return Map.of("strategy", router.getStrategy().toString(), "shards", router.shardIds());
    }
}
