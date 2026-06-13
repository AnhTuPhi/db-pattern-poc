package com.claude.dbpoc.m29.web;

import com.claude.dbpoc.m29.service.HybridService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Endpoints (port 8229):
 *
 *   POST /hybrid/seed?tenants=3&customers=200&orders=5    — populate both tables
 *
 *   --- spine: column queries ---
 *   GET  /hybrid/customer?tenantId=1&email=u4@tenant1.test  — Index Scan on (tenant_id, email)
 *
 *   --- leaves: jsonb operators with GIN ---
 *   GET  /hybrid/customers/with-tag/{tag}                  — profile @> {"tags":[tag]}
 *   GET  /hybrid/orders/with-sku/{sku}                     — items   @> [{"sku":sku}]
 *
 *   --- reporting: lateral unnest of jsonb arrays ---
 *   GET  /hybrid/reports/top-skus?limit=10                 — SUM over jsonb_array_elements(items)
 *   GET  /hybrid/reports/revenue-by-tenant                 — joins customer back to order for tenancy
 *
 *   --- mutations ---
 *   POST /hybrid/customer/{id}/profile?key=preferredChannel&value="sms"
 *
 *   --- meta ---
 *   GET  /hybrid/topology                                  — counts, sizes, indexes
 *   POST /hybrid/anti-pattern                              — split vs embedded comparison
 */
@RestController
@RequestMapping("/hybrid")
public class HybridController {

    private final HybridService svc;

    public HybridController(HybridService svc) { this.svc = svc; }

    @PostMapping("/seed")
    public Map<String, Object> seed(
            @RequestParam(defaultValue = "3") int tenants,
            @RequestParam(defaultValue = "200") int customers,
            @RequestParam(defaultValue = "5") int orders) {
        return svc.seed(tenants, customers, orders);
    }

    @GetMapping("/customer")
    public Map<String, Object> findCustomer(
            @RequestParam Long tenantId, @RequestParam String email) {
        return svc.findCustomer(tenantId, email);
    }

    @GetMapping("/customers/with-tag/{tag}")
    public Map<String, Object> withTag(@PathVariable String tag) {
        return svc.findCustomersWithTag(tag);
    }

    @GetMapping("/orders/with-sku/{sku}")
    public Map<String, Object> withSku(@PathVariable String sku) {
        return svc.findOrdersContainingSku(sku);
    }

    @GetMapping("/reports/top-skus")
    public List<Map<String, Object>> topSkus(@RequestParam(defaultValue = "10") int limit) {
        return svc.topSellingSkus(limit);
    }

    @GetMapping("/reports/revenue-by-tenant")
    public List<Map<String, Object>> revenueByTenant() {
        return svc.revenueByTenant();
    }

    @PostMapping("/customer/{id}/profile")
    public Map<String, Object> patchProfile(
            @PathVariable Long id,
            @RequestParam String key,
            @RequestParam String value) {
        return svc.patchProfile(id, key, value);
    }

    @GetMapping("/topology")
    public Map<String, Object> topology() { return svc.topology(); }

    @PostMapping("/anti-pattern")
    public Map<String, Object> antiPattern() { return svc.antiPatternComparison(); }
}
