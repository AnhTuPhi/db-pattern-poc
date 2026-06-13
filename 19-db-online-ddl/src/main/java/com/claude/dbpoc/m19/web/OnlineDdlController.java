package com.claude.dbpoc.m19.web;

import com.claude.dbpoc.m19.service.OnlineDdlService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Endpoints:
 *
 *   POST /ddl/seed?rows=50000     — seed a non-trivial table.
 *   POST /ddl/add-column-safe     — ALTER ADD COLUMN ... DEFAULT 'X'    (metadata only).
 *   POST /ddl/add-column-unsafe   — ALTER ADD COLUMN ... DEFAULT gen_random_uuid()   (rewrite).
 *   POST /ddl/create-index-concurrently — CREATE INDEX CONCURRENTLY.
 *   GET  /ddl/describe            — current columns + indexes + size.
 *   GET  /ddl/locks               — current pg_locks snapshot.
 *
 * Reset by calling /ddl/seed again.
 */
@RestController
@RequestMapping("/ddl")
public class OnlineDdlController {

    private final OnlineDdlService svc;

    public OnlineDdlController(OnlineDdlService svc) {
        this.svc = svc;
    }

    @PostMapping("/seed")
    public Map<String, Object> seed(@RequestParam(defaultValue = "50000") int rows) {
        return svc.seed(rows);
    }

    @PostMapping("/add-column-safe")
    public Map<String, Object> safe() { return svc.addColumnSafe(); }

    @PostMapping("/add-column-unsafe")
    public Map<String, Object> unsafe() { return svc.addColumnUnsafe(); }

    @PostMapping("/create-index-concurrently")
    public Map<String, Object> cic() { return svc.createIndexConcurrently(); }

    @GetMapping("/describe")
    public Map<String, Object> describe() { return svc.describe(); }

    @GetMapping("/locks")
    public Map<String, Object> locks() { return svc.currentLocks(); }
}
