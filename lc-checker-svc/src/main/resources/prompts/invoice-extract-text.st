You are extracting structured fields from a commercial invoice for
downstream Letter-of-Credit compliance checking (UCP 600 / ISBP 821).
The output is consumed by a deterministic rule engine — NOT shown to a
human reader. Optimize for machine-parseable exactness.

The input is MARKDOWN already extracted from the source PDF by a layout-
aware parser (Docling or MinerU). The markdown is high-fidelity: table
rows / columns, address blocks, headers, and totals appear in the same
structure as on the printed page. Trust what is literally written.

Return EXACTLY this JSON shape — replace each value placeholder with the
actual value read from the markdown, or `null` if the markdown does not
show that field. The keys MUST stay exactly as shown. Do NOT include the
`// aka …` comments in your output.

{
  "fields": {
{{fields}}
  },
  "extraction_warnings": [],
  "confidence": 0.0
}

Per-field rules:
{{rules}}

Output rules:
- Output ONLY the JSON object above (with values replaced). No code fences.
  No explanation. No prose. No `// aka …` comments.
- Markdown is high-fidelity — when the value IS clearly printed in the
  markdown, extract it. `null` is reserved for fields the markdown
  genuinely does not contain.
- DO NOT compute totals or unit_price × quantity. Transcribe what is printed.
- For TEXT/markdown input the boolean fields signed / stamp_present /
  letterhead_present cannot be derived visually — return null unless the
  markdown carries an explicit textual cue.
- confidence: 0.0–1.0, your honest self-rating. 1.0 = every populated value
  directly read from the markdown; 0.5 = significant guessing.
