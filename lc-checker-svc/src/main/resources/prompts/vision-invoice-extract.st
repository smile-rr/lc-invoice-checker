You are extracting fields from a commercial invoice for Letter-of-Credit
compliance review (UCP 600 / ISBP 821). Read the rendered invoice image(s)
carefully and return ONE JSON object — no preamble, no code fences, no
commentary, no markdown rendering of the invoice.

Schema (return EXACTLY this shape):
{
  "fields": { /* canonical key → value (string | number | boolean | null) */ },
  "extraction_warnings": [ /* short strings flagging low-confidence reads */ ],
  "confidence": 0.0
}

Field reference — return `null` when the invoice does NOT show the field:
{{fields}}

Per-field conventions:
- Currency: ISO 4217 three-letter code (USD / EUR / CNY). Never the symbol.
- Dates: ISO 8601 YYYY-MM-DD.
- Amounts: bare number, no thousand separators, no currency prefix.
- HS code: digits only — strip dots and spaces.
- Addresses: preserve multi-line layout — keep newlines as \n in the value.
- goods_description: PRESERVE the original wording verbatim. Do not rephrase
  or shorten. ISBP 821 C3 "corresponds with" check needs the exact text.
- signed / stamp_present / letterhead_present: boolean based on what the
  image shows. Stamp is a coloured/inked round seal; letterhead is the
  beneficiary's printed header / logo at the top.

Rules — read carefully:
- Output ONLY the JSON object. No code fences. No explanation.
- For any field that's blurry, partially occluded, or ambiguous: still write
  your best read AND add a warning to extraction_warnings, e.g.
  "currency code unclear on page 2" or "line item 5 amount partially cut".
- Do NOT compute totals or unit_price × quantity. Transcribe what's printed
  on the invoice. The downstream rule engine validates arithmetic.
- Do NOT include a markdown rendering of the invoice.
- Do NOT judge LC compliance. That is the rule engine's job, not yours.
- Line-item tables: for every cell in every row, return `null` if the invoice
  does not print a value for that column. Never invent a quantity, unit, unit
  price, or HS code that is not on the invoice. If the entire table is absent,
  return `[]`.
- quantity / unit_price scalar fields: if the invoice has exactly ONE product
  line, populate these scalars from that line AND include it in line_items too.
  If the invoice has multiple product lines, leave quantity and unit_price null
  — the rule engine reads line_items directly.
