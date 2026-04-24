# Prompt templates

These `.st` files are rendered by Spring AI's `PromptTemplate` (StringTemplate-backed). They use single-brace `{variable}` placeholders; keep the file contents free of any extraneous `{}` constructs that could be misread as variables.

All prompts assume `temperature = 0.0` for deterministic output, and every prompt explicitly instructs the model to emit **only valid JSON, no markdown fences, no preamble**.

## File index

| File | Stage | Caller | Input variables | Output | UCP / ISBP anchor |
|---|---|---|---|---|---|
| `mt700-45A-parse.st` | MT700 Parser · Part B | `Mt700LlmParser#parse45A` | `field45ARaw` | JSON object of structured goods attributes | Feeds 18(c), 30(a/b), C3, C4, C7, C8 |
| `mt700-46A-parse.st` | MT700 Parser · Part B | `Mt700LlmParser#parse46A` | `field46ARaw` | JSON array of required-document objects | Feeds INV-007 (C1), INV-008 (18d / C2), INV-019 (C7), INV-030 |
| `mt700-47A-parse.st` | MT700 Parser · Part B | `Mt700LlmParser#parse47A` | `field47ARaw` | JSON array of condition objects | V1: parse only; V2 generates dynamic check nodes (INV-031) |
| `invoice-extract.st` | Invoice Extraction · Java-side fallback | `InvoiceLlmExtractor#extract` | `rawMarkdown` | JSON object matching the extractor-service `fields` contract | Safety net when extractor confidence is below threshold |
| `goods-description-check.st` | Rule Execution · Type B | `TypeBStrategy` for INV-015 | `lcGoodsDescription`, `lcGoodsStructured`, `invoiceGoodsDescription`, `invoiceQuantity`, `invoiceUnit` | JSON object `{compliant, quantity_match, description_match, reason, lc_quantity_claimed, invoice_quantity_stated}` | UCP 600 Art. 18(c) / ISBP 821 Para. C3 |

## Editing rules

- **Do not** re-introduce `{{!-- ... --}}` Handlebars-style comments inside `.st` files — Spring AI will try to interpolate them.
- **Do not** add example JSON blocks with `{...}` that look like variable placeholders — escape with `\{` if unavoidable.
- **Do** keep the instruction "return ONLY a JSON object/array, no markdown fences, no prose" in every prompt; we rely on it for deterministic parsing.
- **Do** match the variable names used in the caller Java code (e.g. `{field45ARaw}` in the prompt ↔ `Map.of("field45ARaw", raw)` in Java).

## Adding a new prompt

1. Add the `.st` file here.
2. Add a row to the file index above (input vars, output shape, UCP anchor).
3. Reference the prompt by filename in `catalog.yml` for Type-B rules (`prompt_template: my-new-prompt.st`), or by constant in the Java caller for non-rule prompts.
4. Add a unit test that asserts the rendered prompt contains the expected variable substitutions and no stray braces.
