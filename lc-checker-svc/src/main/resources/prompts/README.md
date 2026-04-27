# Prompt templates

These `.st` files are rendered by Spring AI's `PromptTemplate` (StringTemplate-backed). They use single-brace `{variable}` placeholders; keep the file contents free of any extraneous `{}` constructs that could be misread as variables.

All prompts assume `temperature = 0.0` for deterministic output, and every prompt explicitly instructs the model to emit **only valid JSON, no markdown fences, no preamble**.

## Layout

```
prompts/
  system/
    check-system.st   # bound as the chat .system() message for every AGENT
                      # rule. Holds verdict semantics, standing rules
                      # (always populate evidence; both blank → DOUBTS;
                      # clear mismatch → FAIL not NOT_REQUIRED; whole-doc
                      # mismatch → FAIL via UCP 14(d)), ISBP cross-cutting
                      # tolerances, and the strict JSON output schema.
  check/
    <rule-id>.st      # rule-specific user message: rule identity, authority
                      # excerpts, full LC + invoice payload, applicability
                      # criteria, verdict criteria. Slim by design — the
                      # boilerplate lives in the system prompt above.
  extract/
    invoice-extract-vision.st  # vision-LLM invoice extraction
    invoice-extract-text.st    # text-LLM extraction fallback
```

## File index

| File | Stage | Caller | Input variables | Output | UCP / ISBP anchor |
|---|---|---|---|---|---|
| `system/check-system.st` | Stage 3 · Standing system message | `AgentStrategy` | (none — static body) | n/a (binds verdict semantics + JSON schema) | — |
| `extract/invoice-extract-vision.st` | Stage 1b · Vision extractor | `VisionLlmExtractor` | `pages` (rendered PDF images) | JSON object of 18 invoice fields | feeds all downstream checks |
| `extract/invoice-extract-text.st` | Stage 1b · Java-side fallback | reserved for markdown-to-fields mapping if enabled | `rawMarkdown` | JSON object matching the extractor-service `fields` contract | Safety net for low-confidence extractor output |
| `check/<rule-id>.st` | Stage 3 · AGENT rule | `AgentStrategy` (per-rule) | `ruleId`, `ruleName`, `ruleDescription`, `ucpRef`, `ucpExcerpt`, `isbpRef`, `isbpExcerpt`, `lcText`, `invoiceText`, `lcRelevantFields`, `invoiceRelevantFields` | JSON object `{verdict, lc_value, presented_value, reason, equation_used?}` | per-rule, see `catalog.yml` |

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
