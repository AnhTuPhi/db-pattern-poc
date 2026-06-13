package com.claude.dbpoc.m18.web;

import com.claude.dbpoc.m18.service.ExpandContractService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Walk through the expand/contract pattern phase by phase. The same
 * endpoint can be called multiple times — phase only advances forward.
 *
 *   POST /migration/reset
 *   POST /migration/expand                            — phase 1
 *   POST /migration/dual-write?id=1&name=Ada%20L      — phase 2
 *   POST /migration/backfill                          — phase 3
 *   GET  /migration/dual-read                         — phase 4
 *   POST /migration/switch-reads?id=1&name=Ada%20Loveless — phase 5
 *   POST /migration/contract                          — phase 6
 *   GET  /migration/describe                          — anytime
 */
@RestController
@RequestMapping("/migration")
public class MigrationController {

    private final ExpandContractService svc;

    public MigrationController(ExpandContractService svc) {
        this.svc = svc;
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() { return svc.reset(); }

    @PostMapping("/expand")
    public Map<String, Object> expand() { return svc.expand(); }

    @PostMapping("/dual-write")
    public Map<String, Object> dualWrite(@RequestParam Long id, @RequestParam String name) {
        return svc.dualWrite(id, name);
    }

    @PostMapping("/backfill")
    public Map<String, Object> backfill() { return svc.backfill(); }

    @GetMapping("/dual-read")
    public Map<String, Object> dualRead() { return svc.dualRead(); }

    @PostMapping("/switch-reads")
    public Map<String, Object> switchReads(@RequestParam Long id, @RequestParam String name) {
        return svc.switchReadsToNew(id, name);
    }

    @PostMapping("/contract")
    public Map<String, Object> contract() { return svc.contract(); }

    @GetMapping("/describe")
    public Map<String, Object> describe() { return svc.describe(); }
}
