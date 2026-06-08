package com.claude.dbpoc.m01;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Module 01 — Indexing patterns on Postgres 16.
 *
 * Boots a tiny REST service on :8201 that lets us toggle indexes and bench the
 * same SQL with/without each one. The README walks through the workflow.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
