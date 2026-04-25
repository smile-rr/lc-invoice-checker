package com.lc.checker.api.controller;

import com.lc.checker.infra.persistence.CheckSessionStore;
import com.lc.checker.infra.persistence.CheckSessionStore.SessionSummary;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.lc.checker.domain.session.CheckSession;

/**
 * Lightweight BFF for the UI's "Recent Sessions" list. Backed by
 * {@code v_session_overview} (schema v3) — no full {@code CheckSession}
 * reconstruction, just the scalars a list row needs.
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionsController {

    private final CheckSessionStore store;

    public SessionsController(CheckSessionStore store) {
        this.store = store;
    }

    @GetMapping
    public ResponseEntity<List<SessionSummary>> recent(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(store.findRecent(limit));
    }
}
