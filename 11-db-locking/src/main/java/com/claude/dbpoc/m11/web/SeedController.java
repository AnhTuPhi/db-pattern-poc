package com.claude.dbpoc.m11.web;

import com.claude.dbpoc.m11.domain.Account;
import com.claude.dbpoc.m11.domain.Job;
import com.claude.dbpoc.m11.repo.AccountRepository;
import com.claude.dbpoc.m11.repo.JobRepository;
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
 * Resets account + job tables to a deterministic starting state for the
 * locking demos.
 *
 *   account(1, "alice", 100.00)
 *   job(?, "PENDING", payload="task-N")  × n
 *
 * Call before /locks/* demos. The SKIP LOCKED demo in particular needs
 * a known number of PENDING rows so "did N workers each claim disjoint
 * rows?" can be answered exactly.
 */
@RestController
@RequestMapping("/seed")
public class SeedController {

    private final AccountRepository accountRepo;
    private final JobRepository jobRepo;

    public SeedController(AccountRepository accountRepo, JobRepository jobRepo) {
        this.accountRepo = accountRepo;
        this.jobRepo = jobRepo;
    }

    /**
     * Seed exactly one account (so the FOR UPDATE / NOWAIT / table-lock
     * demos always race on the same row id) and N PENDING jobs.
     *
     * Defaults: 1 account, 50 jobs. 50 is chosen so a 5-worker SKIP
     * LOCKED demo with perWorker=10 dequeues all of them cleanly.
     */
    @PostMapping
    @Transactional
    public Map<String, Object> seed(@RequestParam(defaultValue = "50") int jobs) {
        jobRepo.deleteAllInBatch();
        accountRepo.deleteAllInBatch();

        Account alice = accountRepo.save(new Account("alice", new BigDecimal("100.00")));

        List<Job> seeded = new ArrayList<>(jobs);
        for (int i = 0; i < jobs; i++) {
            seeded.add(new Job("task-" + i));
        }
        jobRepo.saveAll(seeded);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("aliceId", alice.getId());
        out.put("aliceBalance", alice.getBalance());
        out.put("jobsSeeded", jobs);
        out.put("note", "All locking demos use Alice as the lock target. SKIP LOCKED demo dequeues from the job table.");
        return out;
    }
}
