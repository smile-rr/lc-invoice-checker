package com.lc.checker.domain.common;

/**
 * Document categories recognised in MT700 :46A: parsing.
 * {@link #OTHER} is the fallback for unmatched lines — the raw text is preserved
 * on the {@code DocumentRequirement} so rule writers can still inspect it.
 */
public enum DocType {
    COMMERCIAL_INVOICE,
    BILL_OF_LADING,
    AIRWAY_BILL,
    CERT_OF_ORIGIN,
    PACKING_LIST,
    INSURANCE_CERT,
    INSPECTION_CERT,
    WEIGHT_LIST,
    PHYTOSANITARY_CERT,
    OTHER
}
