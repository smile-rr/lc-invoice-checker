package com.lc.checker.rules;

import com.lc.checker.model.Rule;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Immutable in-memory view of the rule catalog. Exposes lookup-by-id and the filtered
 * list of enabled rules — the activator iterates only the enabled set, but the trace
 * serializer needs the full set to explain "rule X was disabled in config".
 */
public final class RuleCatalog {

    private final List<Rule> all;
    private final Map<String, Rule> byId;
    private final List<Rule> enabled;

    public RuleCatalog(List<Rule> rules) {
        this.all = List.copyOf(Objects.requireNonNull(rules, "rules"));
        this.byId = all.stream().collect(Collectors.toUnmodifiableMap(Rule::id, r -> r));
        this.enabled = all.stream().filter(Rule::isEnabled).toList();
    }

    public List<Rule> all() {
        return all;
    }

    public List<Rule> enabled() {
        return enabled;
    }

    public Optional<Rule> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public int size() {
        return all.size();
    }
}
