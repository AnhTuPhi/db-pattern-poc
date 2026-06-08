package com.claude.dbpoc.m03;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runs schema-pg.sql to (re)build the three bait tables.
 *
 * Idempotent: the script drops + recreates, so calling /seed twice is fine.
 * It takes ~60s on a laptop because of the 5M-row transactions table.
 *
 * We don't let Spring's spring.sql.init.* auto-run this on every boot; the
 * data is large and we want explicit, on-demand control.
 */
@RestController
public class SeedController {

    private final JdbcTemplate pg;

    public SeedController(JdbcTemplate pgJdbc) {
        this.pg = pgJdbc;
    }

    @PostMapping("/seed")
    public Map<String, Object> seed() throws IOException {
        long t0 = System.currentTimeMillis();

        // Load the .sql file from the classpath and execute it as one script.
        // JdbcTemplate.execute() handles semicolon-separated statements fine
        // when fed via ScriptUtils, but we keep it minimal here: split-on-;
        // is good enough because the script has no PL/pgSQL blocks.
        String script = StreamUtils.copyToString(
                new ClassPathResource("schema-pg.sql").getInputStream(),
                StandardCharsets.UTF_8);

        for (String stmt : script.split(";\\s*\\n")) {
            String trimmed = stmt.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) continue;
            pg.execute(trimmed);
        }

        long elapsed = System.currentTimeMillis() - t0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("elapsed_ms", elapsed);
        out.put("accounts",     pg.queryForObject("SELECT count(*) FROM accounts",     Long.class));
        out.put("transactions", pg.queryForObject("SELECT count(*) FROM transactions", Long.class));
        out.put("audit_log",    pg.queryForObject("SELECT count(*) FROM audit_log",    Long.class));
        return out;
    }
}
