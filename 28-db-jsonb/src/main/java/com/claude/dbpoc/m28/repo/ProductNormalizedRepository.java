package com.claude.dbpoc.m28.repo;

import com.claude.dbpoc.m28.domain.ProductNormalized;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductNormalizedRepository extends JpaRepository<ProductNormalized, Long> {
}
