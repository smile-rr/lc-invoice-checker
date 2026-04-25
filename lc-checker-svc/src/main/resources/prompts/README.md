# Prompt templates

These `.st` files are rendered by Spring AI's `PromptTemplate` (StringTemplate-backed). They use single-brace `{variable}` placeholders; keep the file contents free of any extraneous `{}` constructs that could be misread as variables.

All prompts assume `temperature = 0.0` for deterministic output, and every prompt explicitly instructs the model to emit **only valid JSON, no markdown fences, no preamble**.

## File index

| File | Stage | Caller | Input variables | Output | UCP / ISBP anchor |
|---|---|---|---|---|---|
| `vision-invoice-extract.st` | Stage 1b · Vision extractor | `VisionLlmExtractor` | `pages` (rendered PDF images) | JSON object of 18 invoice fields | feeds all downstream checks |
| `invoice-extract.st` | Stage 1b · Java-side fallback | reserved for markdown-to-fields mapping if enabled | `rawMarkdown` | JSON object matching the extractor-service `fields` contract | Safety net for low-confidence extractor output |
| `goods-description-check.st` | Stage 3 · Type B (INV-015) | `TypeBStrategy` | `lcGoodsDescription` (:45A: raw), `invoiceGoodsDescription`, `invoiceQuantity`, `invoiceUnit` | JSON object `{compliant, quantity_match, description_match, reason, lc_quantity_claimed, invoice_quantity_stated}` | UCP 600 Art. 18(c) / ISBP 821 Para. C3 |

## Editing rules

- **Do not** re-introduce `{{!-- ... --}}` Handlebars-style comments inside `.st` files — Spring AI will try to interpolate them.
- **Do not** add example JSON blocks with `{...}` that look like variable placeholders — escape with `\{` if unavoidable.
- **Do** keep the instruction "return ONLY a JSON object/array, no markdown fences, no prose" in every prompt; we rely on it for deterministic parsing.
- **Do** match the variable names used in the caller Java code (e.g. `{lcGoodsDescription}` in the prompt ↔ key used in `TypeBStrategy.buildVariables`).

## Adding a new prompt

1. Add the `.st` file here.
2. Add a row to the file index above (input vars, output shape, UCP anchor).
3. Reference the prompt by filename in `catalog.yml` for Type-B rules (`prompt_template: my-new-prompt.st`), or by constant in the Java caller for non-rule prompts.
4. Add a unit test that asserts the rendered prompt contains the expected variable substitutions and no stray braces.
