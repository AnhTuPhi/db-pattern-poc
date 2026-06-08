package com.claude.dbpoc.m07;

import com.claude.dbpoc.common.SqlCounter;
import com.claude.dbpoc.m07.domain.BadChild;
import com.claude.dbpoc.m07.domain.BadParent;
import com.claude.dbpoc.m07.domain.GoodChild;
import com.claude.dbpoc.m07.domain.GoodParent;
import com.claude.dbpoc.m07.repo.BadChildRepository;
import com.claude.dbpoc.m07.repo.BadParentRepository;
import com.claude.dbpoc.m07.repo.GoodParentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The cascade trap collection. Each endpoint isolates ONE surprise and
 * returns the observable evidence (SQL count, what was deleted) so the
 * behaviour can be reasoned about from the response alone.
 *
 * War-story summaries are baked into the response "warning" / "lesson"
 * fields so the README and the endpoint output stay in lock-step.
 */
@RestController
@RequestMapping("/cascade")
public class CascadeDemoController {

    private final BadParentRepository badParentRepo;
    private final BadChildRepository badChildRepo;
    private final GoodParentRepository goodParentRepo;
    private final SqlCounter sqlCounter;

    @PersistenceContext
    private EntityManager em;

    public CascadeDemoController(BadParentRepository badParentRepo,
                                  BadChildRepository badChildRepo,
                                  GoodParentRepository goodParentRepo,
                                  SqlCounter sqlCounter) {
        this.badParentRepo = badParentRepo;
        this.badChildRepo = badChildRepo;
        this.goodParentRepo = goodParentRepo;
        this.sqlCounter = sqlCounter;
    }

    // ---------------------------------------------------------------------
    // /cascade/save-parent
    //   cascade=ALL on @OneToMany → save(parent) cascades PERSIST to children.
    //   The endpoint persists the parent ONLY; children are saved by the cascade.
    //   The friendly half of cascade: this is what people want.
    // ---------------------------------------------------------------------
    @PostMapping("/save-parent")
    @Transactional
    public Map<String, Object> savePARENTwithCascade() {
        sqlCounter.reset();

        BadParent parent = new BadParent("save-demo-parent");
        for (int i = 0; i < 3; i++) {
            parent.addChild(new BadChild("save-demo-child-" + i));
        }

        // Persist the parent only. Hibernate finds the unmanaged children
        // through the cascade=ALL relationship and persists them in the same
        // flush, in FK-safe order.
        badParentRepo.save(parent);
        em.flush();

        long sqlAfter = sqlCounter.getStatementCount();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("parentId", parent.getId());
        out.put("childIds", parent.getChildren().stream().map(BadChild::getId).toList());
        out.put("sqlStatements", sqlAfter);
        out.put("explanation",
                "save(parent) issued 1 parent INSERT + N child INSERTs via cascade=PERSIST. " +
                "This is the *useful* side of cascade — but it ships with the dangerous side bolted on.");
        return out;
    }

    // ---------------------------------------------------------------------
    // /cascade/delete-parent
    //   The dark mirror of /save-parent. cascade=ALL includes REMOVE, so
    //   deleting one parent silently fires N child DELETEs.
    //   In a real codebase this is how "soft delete the customer" turns into
    //   "we lost their order history".
    // ---------------------------------------------------------------------
    @PostMapping("/delete-parent")
    @Transactional
    public Map<String, Object> deleteParentCascades() {
        // Build a fresh parent for the demo so we don't depend on seed state.
        BadParent parent = new BadParent("delete-demo-parent");
        for (int i = 0; i < 3; i++) {
            parent.addChild(new BadChild("delete-demo-child-" + i));
        }
        badParentRepo.saveAndFlush(parent);

        long childCountBefore = parent.getChildren().size();
        Long parentId = parent.getId();

        sqlCounter.reset();
        badParentRepo.delete(parent);
        em.flush();
        long sqlAfter = sqlCounter.getStatementCount();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("parentDeleted", 1);
        out.put("childrenDeleted", childCountBefore);
        out.put("parentIdGone", parentId);
        out.put("sqlStatements", sqlAfter);
        out.put("warning", "REMOVE cascaded across relationship");
        out.put("lesson",
                "cascade=ALL includes REMOVE. delete(parent) fired " + childCountBefore +
                " child DELETEs you did not ask for. Use explicit cascades.");
        return out;
    }

    // ---------------------------------------------------------------------
    // /cascade/orphan-removal
    //   "Safe" way to use orphanRemoval=true: mutate the existing collection
    //   in place. Hibernate sees the diff and deletes only the orphans.
    // ---------------------------------------------------------------------
    @PostMapping("/orphan-removal")
    @Transactional
    public Map<String, Object> orphanRemovalCorrect() {
        BadParent parent = new BadParent("orphan-correct-parent");
        for (int i = 0; i < 4; i++) {
            parent.addChild(new BadChild("orphan-correct-child-" + i));
        }
        badParentRepo.saveAndFlush(parent);
        int before = parent.getChildren().size();

        sqlCounter.reset();
        // The good pattern: clear() leaves the same List instance; Hibernate
        // diffs against its snapshot and emits DELETEs for the absent rows.
        parent.getChildren().clear();
        badParentRepo.saveAndFlush(parent);
        long sqlAfter = sqlCounter.getStatementCount();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("childrenBefore", before);
        out.put("childrenAfter", parent.getChildren().size());
        out.put("sqlStatements", sqlAfter);
        out.put("pattern", "getChildren().clear()  ← mutates the existing collection");
        out.put("lesson",
                "orphanRemoval=true is fine if you mutate the existing collection. " +
                "Hibernate emitted one DELETE per orphan, as expected.");
        return out;
    }

    // ---------------------------------------------------------------------
    // /cascade/orphan-removal-trap
    //   The classic mistake: replacing the collection REFERENCE rather than
    //   mutating it. Even setting it to an EMPTY list orphans everything.
    //   Setting it to a NEW list of the same elements also orphans everything
    //   (because the IDs are equal but the instances aren't, and Hibernate
    //    can't track the new ones as the old).
    // ---------------------------------------------------------------------
    @PostMapping("/orphan-removal-trap")
    @Transactional
    public Map<String, Object> orphanRemovalTrap() {
        BadParent parent = new BadParent("orphan-trap-parent");
        for (int i = 0; i < 4; i++) {
            parent.addChild(new BadChild("orphan-trap-child-" + i));
        }
        badParentRepo.saveAndFlush(parent);
        int before = parent.getChildren().size();

        sqlCounter.reset();
        // The bug. setChildren(new ArrayList<>()) detaches the entire previous
        // collection. orphanRemoval kicks in and deletes every row. The dev
        // intended "start with a fresh collection" — Hibernate heard "drop them all".
        parent.setChildren(new ArrayList<>());
        badParentRepo.saveAndFlush(parent);
        long sqlAfter = sqlCounter.getStatementCount();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("childrenBefore", before);
        out.put("childrenAfter", parent.getChildren().size());
        out.put("childrenDeleted", before);
        out.put("sqlStatements", sqlAfter);
        out.put("antipattern", "setChildren(new ArrayList<>())  ← swaps the reference");
        out.put("warning",
                "Replacing the collection reference under orphanRemoval=true silently " +
                "deletes every previous child. Use getChildren().clear() instead.");
        return out;
    }

    // ---------------------------------------------------------------------
    // /cascade/safe-pattern
    //   GoodParent uses cascade={PERSIST, MERGE} (no REMOVE, no orphanRemoval).
    //   - save works: PERSIST cascades down.
    //   - delete fails until children are removed manually. That refusal IS
    //     the safety: it forces the caller to think about the children.
    // ---------------------------------------------------------------------
    @PostMapping("/safe-pattern")
    @Transactional
    public Map<String, Object> safePattern() {
        Map<String, Object> out = new LinkedHashMap<>();

        // Persist works: PERSIST cascade reaches new children.
        sqlCounter.reset();
        GoodParent parent = new GoodParent("safe-parent");
        for (int i = 0; i < 3; i++) {
            parent.addChild(new GoodChild("safe-child-" + i));
        }
        goodParentRepo.saveAndFlush(parent);
        out.put("savedParentId", parent.getId());
        out.put("savedChildCount", parent.getChildren().size());
        out.put("saveSqlStatements", sqlCounter.getStatementCount());

        // Deleting the parent will throw on flush (FK from good_child.parent_id).
        // Catch it and report — that exception is the feature, not the bug.
        sqlCounter.reset();
        try {
            goodParentRepo.delete(parent);
            em.flush();
            out.put("deleteOutcome", "unexpected: delete succeeded");
        } catch (Exception ex) {
            // Roll back the failed delete so the rest of this @Transactional
            // doesn't poison the persistence context.
            em.clear();
            out.put("deleteOutcome", "rejected by FK — exactly what we want");
            out.put("exceptionType", ex.getClass().getSimpleName());
        }

        out.put("lesson",
                "GoodParent has no REMOVE / orphanRemoval. PERSIST cascades down (useful), " +
                "but deleting the parent fails until you clean up children explicitly. " +
                "The FK error IS the safety net.");
        return out;
    }
}
