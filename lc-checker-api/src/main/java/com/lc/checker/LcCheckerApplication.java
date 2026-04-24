package com.lc.checker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the LC Invoice Checker API.
 *
 * <p>V1 pipeline at a glance (see refer-doc/logic-flow.md):
 * <ol>
 *   <li>Stage 1 — Parsing (MT700 Part A + Part B, Invoice extraction)</li>
 *   <li>Stage 2 — Rule Activation (catalog-driven)</li>
 *   <li>Stage 3 — Check Execution (Type A / B / A+B strategies)</li>
 *   <li>Stage 5 — Output Assembly (DiscrepancyReport JSON)</li>
 * </ol>
 * Layer 3 holistic sweep is intentionally deferred to V2.
 */
@SpringBootApplication
public class LcCheckerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LcCheckerApplication.class, args);
    }
}
