package com.claude.dbpoc.m10.web;

import com.claude.dbpoc.m10.repo.AccountRepository;
import com.claude.dbpoc.m10.service.AnomalyService;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Exposes each anomaly reproduction as a single GET. Every endpoint
 * returns a JSON object describing:
 *   - what isolation level was used
 *   - the readings each thread observed
 *   - whether the anomaly actually fired on Postgres
 *
 * Call /seed before each demo so the starting balance is deterministic.
 */
@RestController
@RequestMapping("/anomaly")
public class AnomalyController {

    private final AnomalyService anomalies;
    private final AccountRepository accountRepo;

    public AnomalyController(AnomalyService anomalies, AccountRepository accountRepo) {
        this.anomalies = anomalies;
        this.accountRepo = accountRepo;
    }

    @GetMapping("/dirty-read")
    public Map<String, Object> dirtyRead() {
        return anomalies.dirtyRead(aliceId());
    }

    @GetMapping("/non-repeatable-read")
    public Map<String, Object> nonRepeatable(
            @RequestParam(defaultValue = "READ_COMMITTED") String isolation) {
        return anomalies.nonRepeatableRead(aliceId(), parseIsolation(isolation));
    }

    @GetMapping("/phantom-read")
    public Map<String, Object> phantom(
            @RequestParam(defaultValue = "READ_COMMITTED") String isolation) {
        return anomalies.phantomRead(parseIsolation(isolation));
    }

    @GetMapping("/lost-update")
    public Map<String, Object> lostUpdate() {
        return anomalies.lostUpdate(aliceId());
    }

    private Long aliceId() {
        return accountRepo.findAll().stream()
                .filter(a -> "alice".equals(a.getOwner()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("call POST /seed first"))
                .getId();
    }

    private int parseIsolation(String name) {
        return switch (name.toUpperCase()) {
            case "READ_UNCOMMITTED" -> TransactionDefinition.ISOLATION_READ_UNCOMMITTED;
            case "READ_COMMITTED"   -> TransactionDefinition.ISOLATION_READ_COMMITTED;
            case "REPEATABLE_READ"  -> TransactionDefinition.ISOLATION_REPEATABLE_READ;
            case "SERIALIZABLE"     -> TransactionDefinition.ISOLATION_SERIALIZABLE;
            default -> throw new IllegalArgumentException("Unknown isolation: " + name);
        };
    }
}
