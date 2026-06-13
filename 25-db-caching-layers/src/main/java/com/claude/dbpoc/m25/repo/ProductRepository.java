package com.claude.dbpoc.m25.repo;

import com.claude.dbpoc.m25.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {}
