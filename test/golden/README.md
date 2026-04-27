# Golden test pairs

Hand-curated LC + invoice combinations that demo and regression-test the
compliance pipeline. Each `invoice-NN-<short-tag>.pdf` is a real invoice
PDF; each `__pass.txt` / `__fail.txt` sibling is a SWIFT MT700 written
specifically against that invoice to produce a known verdict pattern.

## Naming convention

```
invoice-<NN>-<short-tag>.pdf            <- the invoice
invoice-<NN>-<short-tag>__pass.txt      <- LC where this invoice fully complies
invoice-<NN>-<short-tag>__fail.txt      <- LC with deliberate, narrow FAILs
```

Up to 3 invoices × 2 LC variants each → max 6 pairs. Pass tells you the
happy path renders cleanly; fail isolates 2-3 known discrepancies so a
reviewer can verify the verdict surfaces them and only them.

We don't hand-craft DOUBTS test cases — DOUBTS depends on the agent's
confidence calibration, not on inputs alone, so it can't be reliably
constructed. NOT_REQUIRED appears organically when the LC stays silent on
fields the invoice happens to carry (e.g. country of origin without :46A:
asking for it).

## Pairs

### invoice-01-widgets-singapore

Hong-Kong applicant `SINO IMPORTS CO LTD` importing widgets from a
Singapore beneficiary `WIDGET EXPORTS PTE LTD`. Single-line invoice
totalling USD 56,000, signed, CIF terms, POL Port Klang → POD Singapore.

| LC | Expected verdict pattern |
| --- | --- |
| `__pass.txt` | All applicable rules PASS; ISBP-C1, ISBP-C2, ISBP-C4, ISBP-C7, ISBP-C10, UCP-30a, UCP-30b NOT_REQUIRED. |
| `__fail.txt` | 3 FAILs: `UCP-18b-currency` (LC EUR vs invoice USD), `UCP-18b-amount` (LC 30k ±0% vs invoice 56k), `ISBP-C6` (LC beneficiary `RIVAL EXPORTS LTD` vs invoice seller `WIDGET EXPORTS PTE LTD`). All other rules unchanged from pass case. |

### invoice-02-...

(Reserved for the next golden PDF.)

### invoice-03-...

(Reserved for the next golden PDF.)
