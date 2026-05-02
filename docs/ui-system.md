# TradeVerify UI System

> Reference document for design continuity, prototype handoff, and feature redesign.  
> Stack: React 18 + Tailwind CSS 3 + TypeScript · Vite · react-router-dom v6

---

## 1. Design Philosophy

**Financial-terminal aesthetics with Apple HIG legibility.**  
The product is used by trade finance officers under time pressure — every pixel must earn its place. The visual language borrows from financial terminals (monospaced labels, dense data grids, unambiguous status colours) while following Apple HIG for comfortable reading at sustained screen time.

Core principles:
- **Density without clutter** — pack more signal per pixel than a typical SaaS app, but never sacrifice contrast or spatial rhythm.
- **Audit-ready clarity** — every verdict (PASS / FAIL / DOUBTS) and every data cell must be unambiguous; no decorative ambiguity.
- **Zero-surprise navigation** — the 5-step pipeline strip is always visible; the user always knows where they are.
- **Progressive disclosure** — high-level verdict first, evidence on demand via drawers/modals.

---

## 2. Design Tokens

### 2.1 Colour Palette

```
Brand
  navy-1     #1d1d1f   Primary text, nav background, CTA fill
  navy-2     #142236   Hover state for navy surfaces
  navy-3     #1c3050   Nav border, muted navy accent
  teal-1     #0a7e6a   Active/selected, "loaded" states, links
  teal-2     #0e9e86   Brand accent, health dot, running-chip

Surface
  paper      #ffffff   Card backgrounds
  slate2     #f5f5f7   Page background (Apple system grouped)
  line       #d1d1d6   Borders, separators (Apple HIG separator)
  muted      #6e6e73   Secondary labels (WCAG AA on white)

Status — all text values pass WCAG AA (≥4.5:1)
  status-red      #cc0011   FAIL text
  status-redSoft  #fff1f0   FAIL background tint
  status-green    #1a7a43   PASS text
  status-greenSoft #f0fdf4  PASS background tint
  status-gold     #8a5700   DOUBTS / warning text
  status-goldSoft #fefce8   DOUBTS background tint
  status-blue     #0066cc   Info / reference links
  status-blueSoft #eff6ff   Info background tint
```

### 2.2 Typography

| Role | Family | Weight | Size | Tracking |
|------|--------|--------|------|----------|
| Brand logotype | DM Serif Display | — | inherit | — |
| Body / UI labels | SF Pro → DM Sans → system-ui | 400–600 | 14 px base | `-0.01em` |
| Monospaced labels | SF Mono → DM Mono → Menlo | 400–600 | 10–12 px | `0.04em`–`0.22em` |
| Micro-caps labels | sans | 400 | 10 px | `0.2em` (all-caps) |
| Data values | mono | 400 | 11–13 px | default |

Letter-spacing tokens: `display -0.04em` · `heading -0.02em` · `body -0.01em` · `label 0.04em` · `caps 0.08em`

### 2.3 Border Radius

| Token | Value | Usage |
|-------|-------|-------|
| `rounded-card` | 10 px | Card surfaces, drawers |
| `rounded-btn` | 8 px | Buttons, badges, pill chips |
| `rounded-input` | 6 px | Input fields, search |

### 2.4 Animations

| Name | Behaviour | Usage |
|------|-----------|-------|
| `animate-blink` | 1.2s ease infinite | Cursor / live-streaming indicator |
| `animate-flash` | 1s ease-out → transparent | Row highlight on arrival |
| `animate-fadein` | 0.2s ease-out, y+4px | Panel/drawer entry |
| `animate-pulse` (Tailwind) | opacity pulse | Health dot, running chip |
| `api-progress-shimmer` | 1.4s indeterminate bar | API/SSE loading bars |
| `shimmer` (CSS) | sweep left-right | Sample card loading overlay |

---

## 3. Layout System

### 3.1 Shell Chrome

```
┌─────────────────────────── TopNav (h-10, bg-navy-1) ─────────────────────────┐
│  [◆] TradeVerify  UCP 600 + ISBP 821          [● running]  [History ▾]  [New Check] │
└──────────────────────────────────────────────────────────────────────────────┘
┌─────────────────────────── SessionStrip (when in session) ───────────────────┐
│  PASS/FAIL badge  ·  LC ref  ·  Beneficiary → Applicant  ·  Amount  ·  Expiry  ·  CC# │
└──────────────────────────────────────────────────────────────────────────────┘
┌─────────────────────────── PipelineFlow (step progress) ─────────────────────┐
│  ● 00 UPLOAD   —  ● 01 LC PARSE   —  ● 02 INVOICE   —  ● 03 COMPLIANCE CHECK  —  ● 04 REVIEW │
└──────────────────────────────────────────────────────────────────────────────┘
│                                                                                │
│                          Page Content Area                                     │
│                                                                                │
```

**TopNav** (`h-10 bg-navy-1 text-white px-6`):
- Brand: serif logotype + teal-2 hex badge `UCP 600 + ISBP 821`
- Right: health dot → running chip (appears only when a check is active) → History dropdown → New Check CTA

**SessionStrip** (`bg-navy-2 text-white text-xs`):
- Shows when a session is loaded; surfaces LC metadata at a glance
- Status badge left-aligned (PASS = green / FAIL = red token colours)

**PipelineFlow** (step progress strip):
- 5 steps: `00 UPLOAD` → `01 LC PARSE` → `02 INVOICE` → `03 COMPLIANCE CHECK` → `04 REVIEW`
- Active step: filled teal dot + underline; completed: filled dot; pending: open circle
- Sub-labels below each step (model version, confidence score, check count) render live via SSE

### 3.2 Page-level Layouts

**Upload page** (`max-w-5xl mx-auto px-8 py-8`):
- 2-column grid: Letter of Credit slot + Commercial Invoice slot
- Full-width run CTA row below
- Pre-defined samples grid (2-col) below divider

**Session pages** — full-height split-pane pattern:
- **LC Parse (step 01)**: left = MT700 raw source, right = parsed field grid
- **Invoice Extract (step 02)**: left = PDF viewer (zoomable), right = extracted fields list + extractor selector tabs
- **Compliance Check (step 03)**: left sidebar (navigation + filter) + main scrollable rule list + optional source drawer
- **Review (step 04)**: centred card `max-w-[1100px]` with LC/Invoice summary, verdict band, discrepancy list, officer note, decision buttons

### 3.3 Sidebar Pattern (Compliance Check)

```
┌── Left sidebar (w-48, sticky) ─────────────────────┐
│  Navigation: stage label                            │
│  Phase nav: PARTIES / MONEY / GOODS / LOGISTICS …  │
│  Per-phase: count badge (pass / fail counts)        │
└────────────────────────────────────────────────────┘
```

Left sidebar is `sticky top-0` within the scroll container. Active phase highlights with `teal-1` left border accent.

---

## 4. Component Catalogue

### 4.1 SourceSlot (Upload)

Dashed-border card (`border-dashed border-line`) flips to solid teal border (`border-teal-1`) once a file is loaded. Fixed height `h-[140px]`. Drag-and-drop always replaces. Header row: ALLCAPS mono label + "✕ remove" pill (appears only when filled).

### 4.2 SampleCard

`rounded-btn border border-line bg-paper` → hover: `border-teal-2 shadow-sm`. Two-row card: title + scenario badge (top), description text (mid), two FileChip items (bottom). Scenario badge colours: `baseline` = teal-1/10 tint; `image-vision` = gold-soft tint.

### 4.3 PipelineStep Dot

SVG/div circle. States: pending (stroke only) → active (teal fill, glow) → completed (teal fill). Horizontal connector lines between steps (`border-t border-line`).

### 4.4 StatusFilterBar

Horizontal pill row above the rule list. Each pill shows `status · count`. Active pill: `bg-teal-1 text-white`. Inactive: `bg-slate2 border border-line`. Counts act as filter toggles.

### 4.5 RuleCard

Evidence comparison table: LC value (left column) vs Invoice value (right column). Below: verdict reasoning paragraph. Footer: `VIEW SOURCE` ghost button → opens SourceDrawer.

Status badge placement: top-right inline with rule ID. Badge shape: `rounded px-1.5 py-0.5 font-mono text-[9px] uppercase`.

```
┌── RuleCard ──────────────────────────────────────────┐
│  F01 · Issuer (Beneficiary as Seller)      [Agent]   │
│  PARTIES → LLM agent → UCP 600 Art. 18(a) → ISBP … │
│                                                       │
│  ┌── LC ────────────────┬── INVOICE ───────────────┐ │
│  │  Field 59: WIDGET …  │  SELLER: WIDGET …         │ │
│  └──────────────────────┴──────────────────────────┘ │
│                                                       │
│  [PASS] ISBP-C6  Beneficiary name correspondence     │
│  UCP 600 Art. 18(a) and ISBP 821 Para. C6 require …  │
│                                              [VIEW SOURCE] │
└───────────────────────────────────────────────────────┘
```

### 4.6 SourceDrawer

Slides in from right (`translate-x-full → translate-x-0`, `animate-fadein`). Shows raw LC excerpt + raw Invoice excerpt side-by-side with the applied rule citation at top. Close button top-right.

### 4.7 ReviewTab Card

Centred max-width card with `shadow-sm rounded-card border border-line`. Internal sections:
1. **Title bar**: "Review & Sign-off" heading + Export PDF button
2. **Two-col summary**: LC Summary | Invoice Summary (separated by `bg-line` hairline)
3. **Verdict band**: coloured background (`redSoft` / `greenSoft` / `goldSoft`) + verdict badge + summary counts
4. **Discrepancies**: collapsible list, each row = MAJOR badge + rule name + evidence + VIEW SOURCE link
5. **Passed Checks**: collapsed count accordion
6. **Officer's Note**: `<textarea>` placeholder "Add reviewer commentary…"
7. **Decision row**: three radio-style buttons — `Approve` / `Request amendment` / `Reject`; Reviewed timestamp right-aligned

### 4.8 Status Badges

| Status | Text colour | Background | Label |
|--------|-------------|------------|-------|
| PASS | `status-green` | `status-greenSoft` | PASS |
| FAIL | `status-red` | `status-redSoft` | FAIL |
| DOUBTS | `status-gold` | `status-goldSoft` | DOUBTS |
| NOT_REQUIRED | `muted` | `slate2` | N/A |

Badge shape: `px-1.5 py-0.5 rounded font-mono text-[9px] uppercase tracking-wider`.

### 4.9 HealthDot

8×8 px circle in TopNav. Green = healthy, amber = degraded, red = unreachable. No text label — tooltips on hover only.

### 4.10 ConfirmDialog

Modal overlay. Max-width `sm`. Title in `font-semibold text-navy-1`, body in `text-sm text-muted`. Two buttons: `Cancel` (ghost) + primary action (`bg-navy-1 text-white rounded-btn`).

---

## 5. Navigation & Routing

| Route | Component | Description |
|-------|-----------|-------------|
| `/` | `HomePage` | Upload + sample grid |
| `/session/:id` | `SessionPage` | Tabbed pipeline view for a running/completed check |
| `/session/:id?step=01` | SessionPage | Deep-link to specific step |

**Auto-advance**: SSE events drive step transitions automatically. The pipeline strip advances as events arrive; no manual tab clicking required for the happy path.

**History Dropdown**: top-right nav. Lists recent sessions by LC ref, beneficiary, verdict. Clicking re-opens a completed session at its last step.

---

## 6. Data Flow & State

```
File upload / sample select
        ↓
POST /api/checks/start  →  { session_id }
        ↓
SSE stream /api/sessions/:id/events
        ↓ (useCheckSession hook)
SessionState {
  lcDoc, invoiceDoc, checks[], report, currentStep
}
        ↓
PipelineFlow (renders step progress)
ComplianceCheckPanel (rules + field view)
ReviewTab (summary + decision)
```

**SSE events**: `lc_parsed` → `invoice_extracted` → `check_result` (one per rule) → `stage_complete` → `report_ready`.

Each `check_result` event triggers `animate-flash` on the corresponding RuleCard row.

---

## 7. Interaction Patterns

### 7.1 Progressive Reveal
- Upload: empty slots → file loaded → side-by-side preview → run
- Compliance: rule list streams in as SSE events arrive; skeleton placeholders fill from top
- Review: locked until `report_ready` event; shows "Pipeline still running…" with `animate-pulse`

### 7.2 Drill-down to Evidence
Source drawer pattern is used consistently across Compliance and Review:
1. User clicks "VIEW SOURCE" on any rule card
2. `SourceDrawer` slides in from right
3. Shows raw LC field + raw invoice field side-by-side, with rule citation header
4. Dismiss: click overlay or ✕ button

### 7.3 Human Review Routing
- DOUBTS verdict → surfaces inline "needs human review" indicator
- Review tab shows these separately from FAIL discrepancies
- Decision buttons enable after reviewing all FAIL + DOUBTS rows

### 7.4 Confirm on Destructive Navigation
Any navigation away from an active session triggers `ConfirmDialog`. This applies to: TopNav brand click, "New Check" CTA, and browser back. The session persists in history and can be reopened.

---

## 8. Responsive Strategy

The app is **desktop-first** (minimum comfortable width ~1200 px). The compliance check panel uses a `useMediaQueryWide()` hook to conditionally render the left sidebar as an inline panel vs. a collapsed drawer on narrower viewports.

The Review tab card is `max-w-[1100px]` and remains readable at 900 px.  
Upload page is `max-w-5xl` and stays 2-column to ~640 px, then single column.

---

## 9. Print / Export

The Review tab includes a `@media print` stylesheet:
- All chrome (nav, pipeline, PDF viewer) hidden via `visibility: hidden`
- Only `.review-print` surfaces as visible
- `window.print()` called by "Export PDF" button → browser PDF export

---

## 10. Current Screen Inventory

| # | Screenshot | Route | Key UX notes |
|---|-----------|-------|-------------|
| 00 | `00.landing-page.jpg` | `/` | Upload slots + sample grid side-by-side (pass/fail variants) |
| 01 | `01.landing-page-preview.jpg` | `/session/:id?step=00` | Pair preview: MT700 raw left, PDF right; Reset + Run CTA |
| 02 | `02.lc-parser.jpg` | `/session/:id?step=01` | MT700 source left, parsed field grid right; click-to-pin a field |
| 03 | `03.invoice-extract.jpg` | `/session/:id?step=02` | PDF viewer left, extracted fields right; extractor selector with confidence % |
| 04 | `04-1/04-2.compliance-check-passed/failed.jpg` | `/session/:id?step=03` | Rule list with FIELD/RULE/ALL view toggle; left sidebar phase nav |
| 05 | `05-1/05-2.final-review-passed/failed.jpg` | `/session/:id?step=04` | Review & Sign-off card; verdict band; decision buttons |

---

## 11. Design Gaps & Redesign Targets

The following areas are identified for future iteration:

### 11.1 Empty / Loading States
- Rule list skeleton while SSE is connecting: currently shows nothing
- Invoice extractor loading: only a small spinner, no skeleton rows

### 11.2 Mobile / Tablet
- Compliance panel sidebar collapses but no dedicated mobile layout exists
- PDF viewer unusable at <600 px

### 11.3 Accessibility
- Focus management when drawers open/close needs audit
- Colour alone (red/green badges) conveys status — add icons for colour-blind users

### 11.4 History Page
- Currently a dropdown; could become a full `/history` page with filters (date range, verdict, LC ref)

### 11.5 Batch Mode
- No UI for submitting multiple LC/Invoice pairs — all flows are single-document today

### 11.6 Officer Collaboration
- Note field is local-only (V1 does not persist); future: threaded comments, mentions

---

## 12. Interaction Flows

> These flows are written in a format directly consumable by Claude design tools:  
> — `generate_diagram` (Figma MCP) can render any flow below as a FigJam flowchart.  
> — Pass a flow section verbatim as the diagram description.  
> — Use the state/event vocabulary here when prompting for prototype redesigns.

---

### 12.1 Master User Journey

```
[New user arrives]
        │
        ▼
┌─────────────────┐
│  / (Upload)     │  ← Start page, no session
│  Empty slots    │
└────────┬────────┘
         │  uploads LC + Invoice  OR  clicks sample card
         ▼
┌─────────────────┐
│  Pair Preview   │  ← Same URL /, both slots filled
│  MT700 | PDF    │  "Reset" clears back to empty slots
└────────┬────────┘
         │  clicks "Run Compliance Check"
         │  POST /api/checks/start → { session_id }
         ▼
┌─────────────────────────────────────────┐
│  /session/:id   (auto-step)             │
│  Step advances automatically via SSE   │
│  pipeline: lc → invoice → check → review│
└──────────────────────────────────────────┘
         │  report_ready event received
         ▼
┌─────────────────┐
│  Review & Sign- │
│  off (step 04)  │
└────────┬────────┘
         │  officer selects decision
         ▼
    Approve / Request amendment / Reject
         │
         ▼
    [Session persists in History]
    [Can reopen from History dropdown]
```

---

### 12.2 SSE-Driven Pipeline State Machine

Each stage transitions: `pending → running → done | error`. The UI reacts to every event.

```
                   POST /start
                       │
               ┌───────▼────────┐
               │  QUEUED state  │  queue position card shown at step 00
               │  (optional)    │  polls until first pipeline event
               └───────┬────────┘
                       │  status{stage:session, state:started}
                       ▼
               ┌───────────────┐
               │  lc_parse     │  step 01 goes running
               │  running      │  PipelineFlow dot: spinning teal
               └───────┬───────┘
                       │  status{stage:lc_parse, state:completed, data:LcDocument}
                       │  → state.lc populated
                       │  → auto-advance to step 01 if user on upload
                       ▼
               ┌───────────────┐
               │ invoice_extract│  step 02 goes running
               │  running       │  extractor selector shows confidence chips
               └───────┬───────┘
                       │  status{stage:invoice_extract, state:completed, data:InvoiceDocument}
                       │  → state.invoice populated
                       │  → AUTO-ADVANCE: if user on step 02 → jumps to step 03
                       ▼
               ┌───────────────┐
               │  programmatic │  step 03 goes running
               │  running      │  rule cards stream in (animate-flash per card)
               └───────┬───────┘
                       │  rule{data:CheckResult}  (N events, one per rule)
                       │  → state.checks[] appended / deduplicated
                       ▼
               ┌───────────────┐
               │  agent        │  LLM rules append after programmatic rules
               │  running      │  same RuleCard component, tagged [Agent]
               └───────┬───────┘
                       │  complete{data:DiscrepancyReport}
                       │  → state.report populated
                       │  → step 04 (Review) becomes available
                       ▼
               ┌───────────────┐
               │   COMPLETE    │  PipelineFlow all dots filled
               │   (report)    │  Review tab unlocked
               └───────────────┘

  Error path:  error{stage, message}
               → state.error set
               → red banner rendered above content area
               → pipeline stops; no further events expected
```

**Auto-advance rule (one-way only):**  
`invoice_extract → done` while `step === 'invoice'` → fires `setStep('check')` once (ref guard prevents re-fire if user navigates back manually).

---

### 12.3 File Upload Flow

```
SourceSlot (empty)
  │
  ├─ drag & drop file onto card
  │     onDrop → pick(FileList)
  │
  └─ click slot card → native file picker opens
        onChange → pick(FileList)

pick(FileList)
  │
  ├─ [inside session] → ConfirmDialog: "Configure a new check?"
  │     ├─ Cancel → no-op, slot stays unchanged
  │     └─ Confirm → confirmedNewCheck = true, proceed
  │
  └─ [not in session OR already confirmed] →
        validateLcFile / validateInvoiceFile (client-side)
          ├─ errors → red pre-submit validation banner, slot still fills
          └─ clean → slot fills, "✓ loaded · X KB" shown

Both slots filled?
  └─ UploadStep re-renders → SamplePreview (pair preview layout)
     ┌─ Reset → clears both slots, navigates to /
     └─ Run → POST /api/checks/start → nav /session/:id
```

---

### 12.4 Sample Selection Flow

```
UploadPage renders sample grid (2-col)
  │
  └─ user clicks SampleCard
        │
        ├─ [inside session] → ConfirmDialog (same gate as file upload)
        │
        └─ fetchSamplePair(sample, AbortController)
             │
             ├─ loading: previous AbortController cancelled
             │
             ├─ error → red error banner, slots unchanged
             │
             └─ success →
                  setLc(lcFile)
                  setInvoice(invFile)
                  setSelectedSample(sample)   ← enables fast-path at run()
                  → UploadStep flips to SamplePreview
                       Run → startCheckBySample (no re-upload of LC)
```

---

### 12.5 Compliance Check Panel — Rule Interaction

```
ComplianceCheckPanel (step 03)
  │
  ├─ View toggle: [FIELD] | [RULE]
  │     FIELD view: rules grouped by invoice field (F01 Issuer, F02 Buyer …)
  │     RULE  view: rules grouped by business phase (PARTIES / MONEY …)
  │
  ├─ StatusFilterBar: [All] [Pass N] [Fail N] [Doubts N] [N/A N]
  │     click → statusFilter = 'PASS'|'FAIL'|'DOUBTS'|'NOT_REQUIRED'|null
  │     filters both FIELD and RULE views simultaneously
  │
  ├─ Left sidebar (ComplianceSidebar)
  │     scrollspy: active phase highlights as user scrolls rule list
  │     click phase → scrolls rule list to that PhaseSection
  │
  └─ RuleCard interactions:
       ├─ "VIEW SOURCE" button → opens SourceDrawer (slides in from right)
       │     SourceDrawer shows: LC raw evidence | Invoice raw evidence
       │     SourceDrawer close: click overlay OR ✕ button
       │
       ├─ Citation chip (UCP Art. / ISBP Para.) → opens ComplianceReferenceModal
       │     modal shows full UCP/ISBP article text
       │     close: ✕ or click outside
       │
       └─ [DOUBTS] status badge → signals "needs human review"
             highlighted with gold background in sidebar count
```

---

### 12.6 Review & Sign-off Flow

```
ReviewTab (step 04) — locked until report_ready SSE event
  │
  ├─ Loading state: "Pipeline still running…" + animate-pulse
  │
  └─ Report arrived:
       │
       ├─ Verdict band: PASS (green) | FAIL (red) | DOUBTS (gold)
       │     Verdict logic:
       │       any FAIL checks → display FAIL
       │       no FAIL but any DOUBTS → display DOUBTS
       │       no FAIL no DOUBTS → PASS
       │
       ├─ Discrepancy list (FAIL checks)
       │     each row: MAJOR badge · rule name · LC evidence · Invoice evidence
       │     "VIEW SOURCE" → SourceDrawer (same component as step 03)
       │
       ├─ Passed Checks (collapsed accordion, count shown)
       │
       ├─ Not Required (count)
       │
       ├─ Officer's Note (textarea)
       │     V1: local-only, not persisted
       │     placeholder: "Add reviewer commentary…"
       │
       ├─ Decision (radio group, default: pending)
       │     ○ Approve
       │     ○ Request amendment
       │     ○ Reject
       │
       └─ Export PDF button
             → window.print() → @media print stylesheet hides chrome
             → browser Save as PDF captures only .review-print content
```

---

### 12.7 Session History Flow

```
TopNav → "History" dropdown button
  │
  └─ HistoryDropdown opens (fetches GET /api/sessions recent list)
       │
       └─ SessionSummary rows: LC ref · Beneficiary · verdict badge · date
            │
            └─ click row → nav /session/:id
                 │
                 └─ SessionPage mounts (key={id} guarantees fresh state)
                      useCheckSession → GET /api/sessions/:id/trace
                        replays all events into reducer
                        → state restored to completed snapshot
                      active step auto-derives from state (usually 'review' or 'check')
```

---

### 12.8 Navigation Guard Flow (Leave Session)

```
User is on /session/:id AND tries to navigate away:
  │
  ├─ Triggers:
  │     • click TopNav brand logo
  │     • click "New Check" CTA in TopNav
  │     • click SampleCard (first time only)
  │     • pick a new file (first time only)
  │
  └─ ConfirmDialog:
       Title: "Leave the current session?"
       Body:  "Session stays in history — reopen anytime."
       Buttons: [Stay here] (cancel, default) | [Leave session] (confirm)
         ├─ Cancel → no-op
         └─ Confirm → nav('/') OR proceed with new file
```

---

### 12.9 Queue Wait Flow

```
POST /start → status: 'QUEUED'
  │
  └─ nav /session/:id
       step auto-derives → 'upload' (queueContext set)
       QueueWaitCard renders above UploadStep

QueueWaitCard shows:
  position N of depth M
  running session ID (link to jump to it)
  Cancel button → DELETE /api/sessions/:id → nav('/')

SSE stream receives status{stage:session, state:started}
  → queueContext cleared → step advances normally
```

---

### 12.10 Error Recovery Flow

```
SSE error event received:
  │
  └─ state.error = { message, stage }
       → Red banner rendered: "[stage] message"
       → Pipeline stays on last completed step
       → No further events expected

User options after error:
  1. "New Check" → nav('/') → fresh upload
  2. History dropdown → reopen a different session
  3. Pipeline strip → click any completed step to review partial results
     (lc and invoice data may still be visible even if check stage errored)
```

---

### 12.11 How Claude Design Consumes These Flows

**FigJam diagram generation:**  
Paste any numbered flow section above into the `generate_diagram` tool prompt. Use:
- `shape: flowchart` for linear/sequential flows (12.1, 12.3, 12.4, 12.6)
- `shape: state-machine` for event-driven state flows (12.2, 12.5)
- `shape: sequence` for multi-actor handoffs (12.7, 12.9)

**Prototype redesign:**  
When asking Claude to redesign a screen, append the relevant flow section so it understands entry/exit conditions, disabled states, and error paths. Example prompt pattern:
> "Redesign the ReviewTab. Design system: `docs/ui-system.md` §4.7, §5, §9. Interaction flow: §12.6. Keep the verdict band and decision buttons; improve empty/loading states."

**New feature design:**  
Use the flows as the baseline contract. New features that extend a flow (e.g. batch mode, collaboration) should first update the relevant flow section here before any screen design begins.

---

## 13. Design Token Reference Card

```css
/* Quick paste for new components */
bg-navy-1        /* #1d1d1f  — primary bg, nav */
bg-teal-1        /* #0a7e6a  — active/selected */
bg-slate2        /* #f5f5f7  — page bg */
bg-paper         /* #ffffff  — card bg */
border-line      /* #d1d1d6  — default border */
text-muted       /* #6e6e73  — secondary label */

/* Status combos */
bg-status-greenSoft text-status-green   /* PASS */
bg-status-redSoft   text-status-red     /* FAIL */
bg-status-goldSoft  text-status-gold    /* DOUBTS */

/* Radius */
rounded-card     /* 10px — cards */
rounded-btn      /* 8px  — buttons, badges */
rounded-input    /* 6px  — inputs */

/* Font stacks */
font-sans        /* SF Pro → DM Sans → system */
font-mono        /* SF Mono → DM Mono → Menlo */
font-serif       /* DM Serif Display — brand only */

/* Animation */
animate-fadein   /* 0.2s panel entry */
animate-flash    /* 1s row highlight */
animate-blink    /* cursor blink */
```
