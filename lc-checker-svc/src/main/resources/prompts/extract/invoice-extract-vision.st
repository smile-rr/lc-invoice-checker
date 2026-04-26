You are extracting structured fields from a commercial invoice for
downstream Letter-of-Credit compliance checking (UCP 600 / ISBP 821).
The output is consumed by a deterministic rule engine — NOT shown to a
human reader. Optimize for machine-parseable exactness.

Read the rendered invoice image(s) carefully. Multiple pages may be
present — process them in order.

Return EXACTLY this JSON shape — replace each value placeholder with the
actual value read from the invoice, or `null` if the invoice does not
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
- For any field that's blurry, partially occluded, or ambiguous: still write
  your best read AND add a short note to extraction_warnings.
- DO NOT compute totals or unit_price × quantity. Transcribe what is printed.
- confidence: 0.0–1.0, your honest self-rating.
