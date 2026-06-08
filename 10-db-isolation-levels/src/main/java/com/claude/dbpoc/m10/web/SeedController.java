package com.claude.dbpoc.m10.web;

import com.claude.dbpoc.m10.domain.Account;
import com.claude.dbpoc.m10.repo.AccountRepository;
import com.claude.dbpoc.m10.repo.TransferRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resets the account + transfer_log tables to a deterministic starting
 * state so every demo begins from the same numbers.
 *
 *   account(1, "alice", 100.00, version=0)
 *   account(2, "bob",   200.00, version=0)
 *
 * Call this between demos. The lost-update demos in particular need
 * the starting balance to be exactly $100 so the "expected $200, got
 * $150" punchline lands.
 */
@RestController
@RequestMapping("/seed")
public class SeedController {

    private final AccountRepository accountRepo;
    private final TransferRepository transferRepo;

    public SeedController(AccountRepository accountRepo, TransferRepository transferRepo) {
        this.accountRepo = accountRepo;
        this.transferRepo = transferRepo;
    }

    @PostMapping
    @Transactional
    public Map<String, Object> seed() {
        transferRepo.deleteAllInBatch();
        accountRepo.deleteAllInBatch();

        Account alice = accountRepo.save(new Account("alice", new BigDecimal("100.00")));
        Account bob   = accountRepo.save(new Account("bob",   new BigDecimal("200.00")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("aliceId", alice.getId());
        out.put("aliceBalance", alice.getBalance());
        out.put("bobId", bob.getId());
        out.put("bobBalance", bob.getBalance());
        out.put("transferLogCleared", true);
        out.put("note", "Every /anomaly/* and /lost-update/* endpoint uses Alice's account as the canary.");
        return out;
    }
}
