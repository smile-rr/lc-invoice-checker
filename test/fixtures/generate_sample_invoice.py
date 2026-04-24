"""
Generate test/sample_invoice.pdf for the LC Invoice Checker V1 vertical slice.

Crafted to deliberately trigger EXACTLY 3 discrepancies when checked against
refer-doc/sample_lc_mt700.txt (LC reference LC2024-000123, USD 50,000 +/- 10%,
1000 units Industrial Widgets Model IW-2024, CIF Singapore).

The 3 intended discrepancies:
  1. invoice_amount        — USD 56,000.00 exceeds the +10% upper bound (USD 55,000.00)
                              UCP 600 Art. 18(b)
  2. goods_description     — quantity 500 UNITS does not match LC's 1000 UNITS
                              UCP 600 Art. 18(c) / ISBP 821 Para. C3
  3. lc_number_reference   — LC number LC2024-000123 absent from invoice
                              ISBP 821 Para. C1

All other invoice fields match the LC so no other Layer 1/2 V1 rule should fire:
  - Seller (beneficiary): WIDGET EXPORTS PTE LTD, 88 INDUSTRIAL AVENUE, SINGAPORE 638888
  - Buyer  (applicant):   SINO IMPORTS CO LTD, 123 NATHAN ROAD, KOWLOON, HONG KONG
  - Currency: USD
  - Invoice date: 2024-03-20  (well before LC expiry 2024-12-15)
  - Trade terms: CIF SINGAPORE
  - Country of origin: SINGAPORE
  - Port of loading: PORT KLANG, MALAYSIA  (LC :44E:)
  - Port of discharge: SINGAPORE          (LC :44F:)
  - Signed: yes (signature block + stamp)

Usage:
    python3 test/fixtures/generate_sample_invoice.py

Outputs:
    test/sample_invoice.pdf
"""

from __future__ import annotations

from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import (
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)


# Resolve project root relative to this file: test/fixtures/generate_sample_invoice.py
PROJECT_ROOT = Path(__file__).resolve().parents[2]
OUTPUT_PATH = PROJECT_ROOT / "test" / "sample_invoice.pdf"


# --- Crafted invoice values (intentionally trigger the 3 V1 discrepancies) ---

INVOICE_NUMBER = "INV-2024-0317"
INVOICE_DATE = "20 March 2024"

SELLER_NAME = "WIDGET EXPORTS PTE LTD"
SELLER_ADDRESS_LINES = [
    "88 INDUSTRIAL AVENUE",
    "SINGAPORE 638888",
]

BUYER_NAME = "SINO IMPORTS CO LTD"
BUYER_ADDRESS_LINES = [
    "123 NATHAN ROAD",
    "KOWLOON, HONG KONG",
]

CURRENCY = "USD"
QUANTITY_UNITS = 500                # LC requires 1000 — DELIBERATE MISMATCH
UNIT_PRICE = 112.00                  # 500 * 112.00 = 56,000.00 USD
TOTAL_AMOUNT = QUANTITY_UNITS * UNIT_PRICE   # 56,000.00 — exceeds LC tolerance band

GOODS_DESCRIPTION = (
    "INDUSTRIAL WIDGETS MODEL IW-2024"
)

TRADE_TERMS = "CIF SINGAPORE"
PORT_OF_LOADING = "PORT KLANG, MALAYSIA"
PORT_OF_DISCHARGE = "SINGAPORE"
COUNTRY_OF_ORIGIN = "SINGAPORE"

SIGNATORY_NAME = "Lim Wei Ming"
SIGNATORY_TITLE = "Authorised Signatory"


def _money(amount: float) -> str:
    return f"USD {amount:,.2f}"


def build() -> Path:
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    doc = SimpleDocTemplate(
        str(OUTPUT_PATH),
        pagesize=A4,
        leftMargin=20 * mm,
        rightMargin=20 * mm,
        topMargin=20 * mm,
        bottomMargin=20 * mm,
        title="Commercial Invoice",
        author=SELLER_NAME,
    )

    styles = getSampleStyleSheet()
    body = ParagraphStyle(
        "body",
        parent=styles["Normal"],
        fontName="Helvetica",
        fontSize=10,
        leading=13,
    )
    label = ParagraphStyle(
        "label",
        parent=body,
        fontName="Helvetica-Bold",
    )
    title = ParagraphStyle(
        "title",
        parent=styles["Title"],
        fontSize=20,
        leading=24,
        alignment=1,  # centre
    )
    subtitle = ParagraphStyle(
        "subtitle",
        parent=body,
        fontSize=9,
        textColor=colors.grey,
        alignment=1,
    )
    section = ParagraphStyle(
        "section",
        parent=label,
        fontSize=10,
        textColor=colors.HexColor("#2c3e50"),
        spaceBefore=2,
        spaceAfter=2,
    )

    story = []

    # --- Header / seller block ---
    story.append(Paragraph(SELLER_NAME, title))
    story.append(
        Paragraph(
            " &nbsp;&bull;&nbsp; ".join(SELLER_ADDRESS_LINES),
            subtitle,
        )
    )
    story.append(Spacer(1, 6 * mm))

    story.append(Paragraph("COMMERCIAL INVOICE", title))
    story.append(Spacer(1, 4 * mm))

    # --- Invoice meta + Bill To, side-by-side ---
    invoice_meta = [
        [Paragraph("Invoice No.", label), Paragraph(INVOICE_NUMBER, body)],
        [Paragraph("Invoice Date", label), Paragraph(INVOICE_DATE, body)],
        [Paragraph("Currency", label), Paragraph(CURRENCY, body)],
        [Paragraph("Country of Origin", label), Paragraph(COUNTRY_OF_ORIGIN, body)],
    ]
    bill_to_lines = "<br/>".join([BUYER_NAME, *BUYER_ADDRESS_LINES])
    bill_to_block = [
        [Paragraph("Bill To (Buyer / Applicant)", label)],
        [Paragraph(bill_to_lines, body)],
    ]

    meta_table = Table(invoice_meta, colWidths=[40 * mm, 50 * mm])
    meta_table.setStyle(
        TableStyle(
            [
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
                ("TOPPADDING", (0, 0), (-1, -1), 1),
            ]
        )
    )
    bill_table = Table(bill_to_block, colWidths=[80 * mm])
    bill_table.setStyle(
        TableStyle(
            [
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
                ("TOPPADDING", (0, 0), (-1, -1), 1),
                ("BOX", (0, 0), (-1, -1), 0.5, colors.HexColor("#bdc3c7")),
                ("LEFTPADDING", (0, 0), (-1, -1), 6),
                ("RIGHTPADDING", (0, 0), (-1, -1), 6),
            ]
        )
    )

    header_row = Table([[meta_table, bill_table]], colWidths=[90 * mm, 80 * mm])
    header_row.setStyle(
        TableStyle(
            [
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ("LEFTPADDING", (0, 0), (-1, -1), 0),
                ("RIGHTPADDING", (0, 0), (-1, -1), 0),
            ]
        )
    )
    story.append(header_row)
    story.append(Spacer(1, 6 * mm))

    # --- Shipment terms ---
    story.append(Paragraph("Shipment Terms", section))
    shipment = [
        ["Trade Terms", TRADE_TERMS],
        ["Port of Loading", PORT_OF_LOADING],
        ["Port of Discharge", PORT_OF_DISCHARGE],
    ]
    shipment_table = Table(shipment, colWidths=[40 * mm, 130 * mm])
    shipment_table.setStyle(
        TableStyle(
            [
                ("FONTNAME", (0, 0), (0, -1), "Helvetica-Bold"),
                ("FONTSIZE", (0, 0), (-1, -1), 10),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
                ("TOPPADDING", (0, 0), (-1, -1), 1),
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
            ]
        )
    )
    story.append(shipment_table)
    story.append(Spacer(1, 6 * mm))

    # --- Goods table ---
    story.append(Paragraph("Goods Supplied", section))
    goods_header = ["Description", "Quantity", "Unit Price", "Amount"]
    goods_rows = [
        goods_header,
        [
            GOODS_DESCRIPTION,
            f"{QUANTITY_UNITS} UNITS",
            _money(UNIT_PRICE),
            _money(QUANTITY_UNITS * UNIT_PRICE),
        ],
        ["", "", "Subtotal", _money(TOTAL_AMOUNT)],
        ["", "", "Total Amount", _money(TOTAL_AMOUNT)],
    ]

    goods_table = Table(
        goods_rows,
        colWidths=[80 * mm, 30 * mm, 30 * mm, 30 * mm],
    )
    goods_table.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#ecf0f1")),
                ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
                ("ALIGN", (1, 0), (-1, -1), "RIGHT"),
                ("ALIGN", (0, 0), (0, -1), "LEFT"),
                ("FONTSIZE", (0, 0), (-1, -1), 10),
                ("GRID", (0, 0), (-1, 1), 0.5, colors.HexColor("#bdc3c7")),
                ("LINEABOVE", (2, 2), (-1, 2), 0.5, colors.HexColor("#bdc3c7")),
                ("LINEABOVE", (2, 3), (-1, 3), 0.75, colors.black),
                ("FONTNAME", (2, 3), (-1, 3), "Helvetica-Bold"),
                ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
                ("TOPPADDING", (0, 0), (-1, -1), 6),
            ]
        )
    )
    story.append(goods_table)
    story.append(Spacer(1, 8 * mm))

    # --- Declaration (intentionally NO LC number reference) ---
    story.append(Paragraph("Declaration", section))
    declaration_text = (
        "We hereby certify that the goods described above are of "
        f"{COUNTRY_OF_ORIGIN} origin and that this commercial invoice is "
        "true and correct."
    )
    story.append(Paragraph(declaration_text, body))
    story.append(Spacer(1, 12 * mm))

    # --- Signature block (proves invoice is signed) ---
    sig_rows = [
        ["", ""],
        ["_______________________________", ""],
        [SIGNATORY_NAME, ""],
        [SIGNATORY_TITLE, ""],
        [f"For and on behalf of {SELLER_NAME}", ""],
        ["[ COMPANY STAMP ]", ""],
    ]
    sig_table = Table(sig_rows, colWidths=[90 * mm, 80 * mm])
    sig_table.setStyle(
        TableStyle(
            [
                ("FONTSIZE", (0, 0), (-1, -1), 10),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 1),
                ("TOPPADDING", (0, 0), (-1, -1), 1),
                ("FONTNAME", (0, 5), (0, 5), "Helvetica-Oblique"),
                ("TEXTCOLOR", (0, 5), (0, 5), colors.HexColor("#7f8c8d")),
            ]
        )
    )
    story.append(sig_table)

    doc.build(story)
    return OUTPUT_PATH


if __name__ == "__main__":
    out = build()
    print(f"Wrote {out}")
