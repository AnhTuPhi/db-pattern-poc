package com.claude.dbpoc.m14.repo;

import com.claude.dbpoc.m14.domain.Widget;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface WidgetRepository extends JpaRepository<Widget, Long> {

    /** SELECT FOR UPDATE — used by the lock-hold demo. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Widget w where w.id = ?1")
    Optional<Widget> findByIdForUpdate(Long id);
}
