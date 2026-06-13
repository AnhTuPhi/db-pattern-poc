package com.claude.dbpoc.m30.web;

import com.claude.dbpoc.m30.service.SchemaPerTenantService;
import com.claude.dbpoc.m30.service.SharedSchemaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Endpoints (port 8230).
 *
 * <p>Every endpoint expects the {@code X-Tenant-Id} header. The
 * {@link com.claude.dbpoc.m30.tenant.TenantFilter} reads it and
 * stashes it on {@link com.claude.dbpoc.m30.tenant.TenantContext};
 * the service layer reads from there.
 *
 * <pre>
 *   --- Strategy 1 : shared schema + RLS ---
 *   POST /shared/product?sku=A&name=foo&price=9.99    -H 'X-Tenant-Id: 1'
 *   GET  /shared/products                              -H 'X-Tenant-Id: 1'
 *   GET  /shared/visibility                            -H 'X-Tenant-Id: 1'
 *   GET  /shared/breach/2                              -H 'X-Tenant-Id: 1'
 *
 *   --- Strategy 2 : schema-per-tenant ---
 *   POST /perschema/onboard/{tid}
 *   POST /perschema/product?sku=A&name=foo&price=9.99 -H 'X-Tenant-Id: 1'
 *   GET  /perschema/products                          -H 'X-Tenant-Id: 1'
 *   GET  /perschema/global-counts
 * </pre>
 */
@RestController
public class TenancyController {

    private final SharedSchemaService shared;
    private final SchemaPerTenantService perSchema;

    public TenancyController(SharedSchemaService shared, SchemaPerTenantService perSchema) {
        this.shared = shared;
        this.perSchema = perSchema;
    }

    // ─── Strategy 1: shared schema + RLS ─────────────────────────────────

    @PostMapping("/shared/product")
    public Map<String, Object> sharedAdd(
            @RequestParam String sku,
            @RequestParam String name,
            @RequestParam BigDecimal price) {
        return shared.addProduct(sku, name, price);
    }

    @GetMapping("/shared/products")
    public List<Map<String, Object>> sharedList() {
        return shared.listProducts();
    }

    @GetMapping("/shared/visibility")
    public Map<String, Object> sharedVisibility() {
        return shared.visibility();
    }

    @GetMapping("/shared/breach/{foreignTenantId}")
    public Map<String, Object> sharedBreach(@PathVariable Long foreignTenantId) {
        return shared.attemptBreach(foreignTenantId);
    }

    // ─── Strategy 2: schema per tenant ───────────────────────────────────

    @PostMapping("/perschema/onboard/{tid}")
    public Map<String, Object> onboard(@PathVariable Long tid) {
        return perSchema.onboardTenant(tid);
    }

    @PostMapping("/perschema/product")
    public Map<String, Object> perSchemaAdd(
            @RequestParam String sku,
            @RequestParam String name,
            @RequestParam BigDecimal price) {
        return perSchema.addProduct(sku, name, price);
    }

    @GetMapping("/perschema/products")
    public List<Map<String, Object>> perSchemaList() {
        return perSchema.listProducts();
    }

    @GetMapping("/perschema/global-counts")
    public List<Map<String, Object>> perSchemaCounts() {
        return perSchema.globalProductCount();
    }
}
