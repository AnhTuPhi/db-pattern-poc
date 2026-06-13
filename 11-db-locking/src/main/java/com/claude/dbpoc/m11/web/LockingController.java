package com.claude.dbpoc.m11.web;

import com.claude.dbpoc.m11.repo.AccountRepository;
import com.claude.dbpoc.m11.service.LockingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * One endpoint per locking primitive. Each one returns a JSON object
 * describing what each thread observed (wait time, exception class,
 * lock state) so the behaviour is visible without attaching a debugger.
 *
 *   /locks/for-update           — plain FOR UPDATE; T2 blocks behind T1.
 *   /locks/skip-locked          — N workers claim disjoint rows from job table.
 *   /locks/nowait               — T2 fails-fast with SQLSTATE 55P03.
 *   /locks/table                — LOCK TABLE EXCLUSIVE blocks SELECT.
 *   /locks/observability        — pg_locks + pg_stat_activity snapshot.
 *
 * Always call POST /seed before each demo so starting state is deterministic.
 */
@RestController
@RequestMapping("/locks")
public class LockingController {

    private final LockingService locks;
    private final AccountRepository accountRepo;

    public LockingController(LockingService locks, AccountRepository accountRepo) {
        this.locks = locks;
        this.accountRepo = accountRepo;
    }

    @GetMapping("/for-update")
    public Map<String, Object> forUpdate() {
        return locks.forUpdate(aliceId());
    }

    /**
     * Spin up `workers` workers, each claims `perWorker` jobs. With
     * defaults (5 workers × 10 jobs) the demo drains the seeded 50
     * PENDING rows exactly once with zero overlap.
     */
    @GetMapping("/skip-locked")
    public Map<String, Object> skipLocked(
            @RequestParam(defaultValue = "5")  int workers,
            @RequestParam(defaultValue = "10") int perWorker) {
        return locks.skipLocked(workers, perWorker);
    }

    @GetMapping("/nowait")
    public Map<String, Object> nowait() {
        return locks.nowait(aliceId());
    }

    @GetMapping("/table")
    public Map<String, Object> tableLevel() {
        return locks.tableLevel();
    }

    @GetMapping("/observability")
    public Map<String, Object> observability() {
        return locks.observability(aliceId());
    }

    /**
     * Always re-resolve Alice's id from the repo so the demo continues to
     * work after a re-seed (which gives Alice a new id from the IDENTITY
     * sequence).
     */
    private Long aliceId() {
        return accountRepo.findAll().stream()
                .filter(a -> "alice".equals(a.getOwner()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("call POST /seed first"))
                .getId();
    }
}
