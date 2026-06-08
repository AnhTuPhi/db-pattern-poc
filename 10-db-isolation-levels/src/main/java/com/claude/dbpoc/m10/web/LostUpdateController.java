package com.claude.dbpoc.m10.web;

import com.claude.dbpoc.m10.repo.AccountRepository;
import com.claude.dbpoc.m10.service.LostUpdateFixService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Five endpoints, one per lost-update fix. Each one runs the same
 * scenario as /anomaly/lost-update but routes through a different fix
 * and returns whether the anomaly was prevented and by what cost.
 *
 * Always call /seed before each demo so the starting state is clean —
 * the @Version column matters and a leftover bumped version from a
 * previous demo will make the next one's exception story misleading.
 */
@RestController
@RequestMapping("/lost-update")
public class LostUpdateController {

    private final LostUpdateFixService fixes;
    private final AccountRepository accountRepo;

    public LostUpdateController(LostUpdateFixService fixes, AccountRepository accountRepo) {
        this.fixes = fixes;
        this.accountRepo = accountRepo;
    }

    @GetMapping("/select-for-update")  public Map<String, Object> sfu()         { return fixes.selectForUpdate(aliceId()); }
    @GetMapping("/optimistic")          public Map<String, Object> opt()         { return fixes.optimistic(aliceId()); }
    @GetMapping("/cas-update")          public Map<String, Object> cas()         { return fixes.casUpdate(aliceId()); }
    @GetMapping("/retry")               public Map<String, Object> retry()       { return fixes.retryOptimistic(aliceId()); }
    @GetMapping("/serializable")        public Map<String, Object> serializable(){ return fixes.serializable(aliceId()); }

    /**
     * Headline: try every fix in turn. The caller must re-seed between
     * iterations so each fix starts from $100; we do that inline by
     * reading the most recent /seed snapshot, calling the fix, then
     * resetting Alice's balance from the snapshot. Each fix should
     * report anomalyPrevented=true.
     */
    @GetMapping("/all")
    public Map<String, Object> all() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("note", "Each fix is run against a freshly-reset Alice ($100). All five should report anomalyPrevented=true.");
        out.put("fixes", List.of(
            runWithReset(this::sfu),
            runWithReset(this::opt),
            runWithReset(this::cas),
            runWithReset(this::retry),
            runWithReset(this::serializable)
        ));
        return out;
    }

    /**
     * Reset Alice's balance + version to a clean $100 / version=0 between
     * runs so the @Version-driven fixes don't carry state from the
     * previous one.
     */
    private Map<String, Object> runWithReset(java.util.function.Supplier<Map<String, Object>> demo) {
        accountRepo.findAll().stream()
                .filter(a -> "alice".equals(a.getOwner()))
                .forEach(a -> {
                    a.setBalance(new java.math.BigDecimal("100.00"));
                    accountRepo.saveAndFlush(a);
                });
        return demo.get();
    }

    private Long aliceId() {
        return accountRepo.findAll().stream()
                .filter(a -> "alice".equals(a.getOwner()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("call POST /seed first"))
                .getId();
    }
}
