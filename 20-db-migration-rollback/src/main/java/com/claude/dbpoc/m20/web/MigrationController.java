package com.claude.dbpoc.m20.web;

import com.claude.dbpoc.m20.service.MigrationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 *   GET  /flyway/history    — flyway_schema_history rows.
 *   GET  /flyway/info       — Flyway's view of what's on the classpath vs applied.
 *   GET  /flyway/describe   — current state of the product table.
 *   POST /flyway/migrate    — re-run flyway.migrate() (useful after adding files).
 */
@RestController
@RequestMapping("/flyway")
public class MigrationController {

    private final MigrationService svc;

    public MigrationController(MigrationService svc) {
        this.svc = svc;
    }

    @GetMapping("/history")
    public Map<String, Object> history() { return svc.history(); }

    @GetMapping("/info")
    public Map<String, Object> info() { return svc.info(); }

    @GetMapping("/describe")
    public Map<String, Object> describe() { return svc.describe(); }

    @PostMapping("/migrate")
    public Map<String, Object> migrate() { return svc.migrate(); }
}
