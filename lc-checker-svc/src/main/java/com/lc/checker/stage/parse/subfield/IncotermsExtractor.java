package com.lc.checker.stage.parse.subfield;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Scans free-text :45A: (Goods Description) for an Incoterms 2020 token.
 * Mirrors the same vocabulary as {@code field-pool.yaml} {@code incoterms.enum_values}.
 */
@Component
public class IncotermsExtractor {

    private static final List<String> TERMS = List.of(
            "CIF", "CFR", "FOB", "EXW", "FCA", "CPT", "CIP", "DAP", "DPU", "DDP");

    private static final Pattern TOKEN = Pattern.compile(
            "(?i)\\b(CIF|CFR|FOB|EXW|FCA|CPT|CIP|DAP|DPU|DDP)\\b");

    public String extract(String goodsDescription) {
        if (goodsDescription == null || goodsDescription.isBlank()) return null;
        var m = TOKEN.matcher(goodsDescription);
        if (m.find()) {
            return m.group(1).toUpperCase();
        }
        return null;
    }

    public List<String> vocabulary() {
        return TERMS;
    }
}
