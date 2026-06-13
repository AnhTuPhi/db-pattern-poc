package com.claude.dbpoc.m12.web;

import com.claude.dbpoc.m12.domain.Account;
import com.claude.dbpoc.m12.repo.AccountRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Seed exactly two accounts so the deadlock race has TWO ids to fight over.
 *
 *   account(?, "alice", 1000.00)
 *   account(?, "bob",   1000.00)
 *
 * Both have $1000 so the transfer demos can run many iterations without
 * draining either balance. Always re-seed between demos so the deadlock
 * /retry endpoint doesn't accumulate partial state.
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
    public Map<String, Object> seed() {
        accountRepo.deleteAllInBatch();
        Account alice = accountRepo.save(new Account("alice", new BigDecimal("1000.00")));
        Account bob   = accountRepo.save(new Account("bob",   new BigDecimal("1000.00")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("aliceId", alice.getId());
        out.put("bobId", bob.getId());
        out.put("aliceBalance", alice.getBalance());
        out.put("bobBalance", bob.getBalance());
        out.put("note", "Use these ids in the /deadlock/* endpoints as idA and idB.");
        return out;
    }
}
