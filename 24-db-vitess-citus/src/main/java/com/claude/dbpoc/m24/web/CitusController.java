package com.claude.dbpoc.m24.web;

import com.claude.dbpoc.m24.service.CitusService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Endpoints (port 8224, connects to Citus coordinator on 5433):
 *
 *   POST /citus/setup                       — install extension, register workers
 *   POST /citus/seed?tenants=200&ordersPerTenant=10
 *   GET  /citus/single-tenant/{tenantId}    — Task Count: 1
 *   GET  /citus/cross-tenant                — Task Count: N (scatter/gather)
 *   GET  /citus/colocated-join              — orders ⨝ order_items, local per worker
 *   GET  /citus/reference-join              — orders ⨝ regions, local per worker
 *   GET  /citus/topology                    — nodes + distributed-table catalog
 */
@RestController
@RequestMapping("/citus")
public class CitusController {

    private final CitusService svc;

    public CitusController(CitusService svc) { this.svc = svc; }

    @PostMapping("/setup")
    public Map<String, Object> setup() { return svc.setupCluster(); }

    @PostMapping("/seed")
    public Map<String, Object> seed(@RequestParam(defaultValue = "200") int tenants,
                                    @RequestParam(defaultValue = "10") int ordersPerTenant) {
        return svc.seed(tenants, ordersPerTenant);
    }

    @GetMapping("/single-tenant/{tenantId}")
    public Map<String, Object> single(@PathVariable long tenantId) {
        return svc.singleTenant(tenantId);
    }

    @GetMapping("/cross-tenant")
    public Map<String, Object> cross() { return svc.crossTenant(); }

    @GetMapping("/colocated-join")
    public Map<String, Object> colocated() { return svc.colocatedJoin(); }

    @GetMapping("/reference-join")
    public Map<String, Object> reference() { return svc.referenceTableJoin(); }

    @GetMapping("/topology")
    public Map<String, Object> topology() { return svc.topology(); }
}
