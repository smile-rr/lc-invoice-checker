# Golden test pairs

Hand-curated LC + invoice combinations that demo and regression-test the
compliance pipeline. Each `<NN>-<short-tag>.pdf` is a real invoice PDF;
each `__pass.txt` / `__fail.txt` sibling is a SWIFT MT700 written
specifically against that invoice to produce a known verdict pattern.

This folder is the **source of truth**. At service startup the API seeds
the MinIO `lc-pdfs` bucket under the `samples/` prefix from the files
here, and the UI's pre-defined sample picker reads them via
`GET /api/v1/samples`.

## Naming convention

```
<NN>-<short-tag>.pdf            <- the invoice
<NN>-<short-tag>__pass.txt      <- LC where this invoice fully complies
<NN>-<short-tag>__fail.txt      <- LC with deliberate, narrow FAILs
```

Pass tells you the happy path renders cleanly; fail isolates 2-3 known
discrepancies so a reviewer can verify the verdict surfaces them and only
them.

We don't hand-craft DOUBTS test cases — DOUBTS depends on the agent's
confidence calibration, not on inputs alone, so it can't be reliably
constructed. NOT_REQUIRED appears organically when the LC stays silent on
fields the invoice happens to carry (e.g. country of origin without :46A:
asking for it).

## Pairs

### 01-widgets-singapore

Hong-Kong applicant `SINO IMPORTS CO LTD` importing widgets from a
Singapore beneficiary `WIDGET EXPORTS PTE LTD`. Single-line invoice
totalling USD 56,000, signed, CIF terms, POL Port Klang → POD Singapore.

| LC | Expected verdict pattern |
| --- | --- |
| `__pass.txt` | All applicable rules PASS; ports + tolerance NOT_REQUIRED checks neutral. |
| `__fail.txt` | 3 FAILs: `UCP-18b-currency` (LC EUR vs invoice USD), `UCP-18b-amount` (LC 30k ±0% vs invoice 56k), `ISBP-C6` (LC beneficiary `RIVAL EXPORTS LTD` vs invoice seller `WIDGET EXPORTS PTE LTD`). |

### 02-apparel-acme

Dutch buyer `DESPORTS` ordering apparel (`T-Shirts and Shorts`) from
seller `ACME` in Lisbon. USD 2,000, DAP terms, no POL/POD on the invoice.

| LC | Expected verdict pattern |
| --- | --- |
| `__pass.txt` | Currency / amount / parties / goods all align; ports NOT_APPLICABLE. |
| `__fail.txt` | 3 FAILs: currency (EUR vs USD), amount (1,500 vs 2,000), beneficiary (`RIVAL APPAREL HOLDINGS` vs `ACME`). |

### 03-painting-artfinder

UK buyer `ANGELA ROLDAN` purchasing an original oil painting from artist
`CESCA FALATO` (Barcelona). GBP 100, DDP, no shipping ports — sold via
Artfinder.

| LC | Expected verdict pattern |
| --- | --- |
| `__pass.txt` | Goods / parties / currency / amount align; ports + dates NOT_APPLICABLE. |
| `__fail.txt` | 3 FAILs: currency (USD vs GBP), amount (500 vs 100), beneficiary (`OTHER GALLERY LTD` vs `CESCA FALATO`). |

### 04-boots-baton-rouge

US buyer in Baton Rouge, Louisiana ordering YORK rich-cocoa welted chukka
boots from `GLOBAL UK LIMITED` (London). USD 490, FOB.

| LC | Expected verdict pattern |
| --- | --- |
| `__pass.txt` | Currency / amount / parties / goods align; POL/POD NOT_APPLICABLE. |
| `__fail.txt` | 3 FAILs: currency (EUR vs USD), amount (300 vs 490), beneficiary (`DIFFERENT BOOT EXPORTER LTD` vs `GLOBAL UK LIMITED`). |
