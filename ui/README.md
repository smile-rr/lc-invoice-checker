# LC Checker UI

React + Vite + Tailwind single-page app for the LC Invoice Checker.

## Requirements

- Node.js ≥ 18
- Backend `lc-checker-svc` running on `http://localhost:8080`
- Docling extractor on `http://localhost:8081`

## Run

```bash
# Terminal 1 — backend + extractor
make docling-up
( cd lc-checker-svc && ./gradlew bootRun )

# Terminal 2 — UI
cd ui
npm install
npm run dev
# → http://localhost:5173
```

## Scripts

| Command          | What it does                                   |
|------------------|------------------------------------------------|
| `npm run dev`    | Vite dev server, proxies `/api` to `:8080`     |
| `npm run build`  | Type-check + production build to `dist/`      |
| `npm run preview`| Preview the production build on `:4173`        |
| `npm run lint`   | Type-check only (`tsc --noEmit`)              |

## How it works

1. **Upload** — pick a MT700 `.txt` + invoice `.pdf` (or use the "Try with sample"
   dropdown). Submits to `POST /api/v1/lc-check/start`.
2. **Live** — subscribes to `GET /api/v1/lc-check/{sessionId}/stream` (SSE) and
   drives every UI state from real events: stage transitions, LC/invoice
   renders, per-rule completion.
3. **Report** — on `report.complete`, renders verdict + discrepancies. A "View
   trace" button pulls full forensic data from `GET /{sessionId}/trace`.

## Sample files

LC text: `docs/refer-doc/sample_lc_mt700.txt`
Invoices: `docs/refer-doc/invoice-*.pdf` — the sample pair intentionally shows
discrepancies so you can see the FAIL verdict path.

## Design tokens

Mirrored from `refer-doc/lc_checker_v2.html`. Changes to the palette / typography
should update both the HTML mockup and `tailwind.config.ts`.
