package com.claude.dbpoc.m13.web;

import com.claude.dbpoc.m13.domain.Account;
import com.claude.dbpoc.m13.repo.AccountRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seed N accounts with the same starting balance + version=0.
 *
 * The benchmark needs:
 *   - At least 1 account (id=1) for hot-row mode.
 *   - accountCount accounts for dispersed mode (id 1..accountCount).
 *
 * Default seed = 32 accounts so any threads parameter up to 32 will find
 * its own row in dispersed mode without modular wrap-around.
 *
 * Call this between bench/* runs so each strategy starts from the same
 * state. The hot-row pessimistic run can add $tens-of-thousands to the
 * balance over a full bench — leaving stale state between runs makes the
 * numbers nonsensical.
 */
@RestController
@RequestMapping("/seed")
public class SeedController {

    private final AccountRepository accountRepo;

    public SeedController(AccountRepository accountRepo) {
        this.accountRepo = accountRepo;
    }

    @PostMapping
    @Transactional
    public Map<String, Object> seed(@RequestParam(defaultValue = "32") int accounts) {
        accountRepo.deleteAllInBatch();

        List<Account> seeded = new ArrayList<>(accounts);
        for (int i = 0; i < accounts; i++) {
            seeded.add(new Account("acct-" + i, new BigDecimal("0.00")));
        }
        accountRepo.saveAll(seeded);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accountsSeeded", accounts);
        out.put("firstId", seeded.get(0).getId());
        out.put("lastId", seeded.get(seeded.size() - 1).getId());
        out.put("note",
            "All accounts start at $0.00, version=0. Hot-row mode hits id=" +
            seeded.get(0).getId() + " for every thread; dispersed rotates 1.." +
            seeded.size() + ".");
        return out;
    }
}
