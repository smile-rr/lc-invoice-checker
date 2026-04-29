import { useState } from 'react';
import type { CheckResult, DiscrepancyReport, InvoiceDocument, LcDocument } from '../../types';
import { intField, numField, strField } from '../../lib/envelope';
import { SourceDrawer, type DrawerTarget } from '../check/SourceDrawer';

interface Props {
  sessionId: string | null;
  lc: LcDocument | undefined;
  invoice: InvoiceDocument | undefined;
  checks: CheckResult[];
  report: DiscrepancyReport | undefined;
}

type Decision = 'pending' | 'approve' | 'amend' | 'reject';

export function ReviewTab({ sessionId, lc, invoice, checks, report }: Props) {
  const [note, setNote] = useState('');
  const [decision, setDecision] = useState<Decision>('pending');
  // Same source viewer as the Compliance Check panel — opens on-demand from
  // any rule row below. Reuses one component, so updates land everywhere.
  const [drawerTarget, setDrawerTarget] = useState<DrawerTarget>(null);
  const openSource = (c: CheckResult) =>
    setDrawerTarget({
      ruleId: c.rule_id,
      ruleName: c.rule_name ?? c.rule_id,
      lcEvidence: c.lc_value,
      invoiceEvidence: c.presented_value,
    });

  if (!report) {
    return (
      <div className="px-8 py-12 text-center">
        <div className="font-sans text-lg font-semibold text-navy-1 mb-1 animate-pulse">
          Pipeline still running…
        </div>
        <div className="text-sm text-muted">
          The review summary will appear once Stage 5 finalises the report.
        </div>
      </div>
    );
  }

  const discs = checks.filter((c) => c.status === 'FAIL');
  const passes = checks.filter((c) => c.status === 'PASS');
  const unverified = checks.filter((c) => c.status === 'DOUBTS');
  const notReq = checks.filter((c) => c.status === 'NOT_REQUIRED');

  // Display verdict: FAIL if any discrepancy, DOUBTS if any unverified (no FAIL),
  // PASS only when no FAIL and no DOUBTS. Overrides report.compliant which only
  // reflects FAIL count.
  const displayVerdict: 'PASS' | 'FAIL' | 'DOUBTS' =
    discs.length > 0 ? 'FAIL' : unverified.length > 0 ? 'DOUBTS' : 'PASS';

  return (
    <div className="px-6 py-6">
      <div className="review-print max-w-[1100px] mx-auto bg-paper rounded-card border border-line shadow-sm">
        {/* Title bar */}
        <div className="px-6 py-4 border-b border-line flex items-center gap-3">
          <h2 className="font-sans text-xl font-semibold text-navy-1">Review &amp; Sign-off</h2>
          <div className="ml-auto flex items-center gap-2">
            <button
              onClick={() => window.print()}
              className="text-xs px-3 py-1.5 rounded-btn border border-line text-muted hover:border-teal-2 hover:text-navy-1 transition-colors"
            >
              Export PDF
            </button>
          </div>
        </div>

        {/* Two-card summary */}
        <div className="grid grid-cols-2 gap-px bg-line">
          <SummaryCard title="LC Summary"      body={lcSummary(lc)} />
          <SummaryCard title="Invoice Summary" body={invoiceSummary(invoice)} />
        </div>

        {/* Verdict band */}
        <div
          className={[
            'px-6 py-4 border-b border-line flex items-center gap-4',
            displayVerdict === 'FAIL'
              ? 'bg-status-redSoft'
              : displayVerdict === 'DOUBTS'
              ? 'bg-status-goldSoft'
              : 'bg-status-greenSoft',
          ].join(' ')}
        >
          <span
            className={[
              'px-3 py-1.5 rounded font-mono text-[12px] font-bold',
              displayVerdict === 'FAIL'
                ? 'bg-status-red text-white'
                : displayVerdict === 'DOUBTS'
                ? 'bg-status-gold text-white'
                : 'bg-status-green text-white',
            ].join(' ')}
          >
            {displayVerdict}
          </span>
          <div className="text-sm">
            <span className="font-semibold text-navy-1">
              {discs.length} discrepan{discs.length === 1 ? 'cy' : 'cies'}
            </span>
            <span className="text-muted">
              {' · '}
              {passes.length} passed
              {' · '}
              {unverified.length} unable to verify
              {' · '}
              {report.summary.not_required} N/A
            </span>
          </div>
        </div>

        {/* Discrepancies */}
        {discs.length > 0 && (
          <Section title="Discrepancies">
            <ul className="space-y-3">
              {discs.map((c) => {
                const refs = [c.ucp_ref, c.isbp_ref].filter(Boolean).join(' · ');
                return (
                  <li
                    key={c.rule_id}
                    className="border-l-4 border-l-status-red pl-4 py-2 bg-status-redSoft/20 rounded-r-sm"
                  >
                    <div className="flex items-baseline gap-3">
                      {/* Rule ID — monospace (it's a code), 12px minimum */}
                      <span className="font-mono text-xs text-muted shrink-0 w-24">
                        {c.rule_id}
                      </span>
                      {/* Rule name — sans-serif, 14px */}
                      <span className="font-sans text-sm font-medium text-navy-1 flex-1">
                        {c.rule_name ?? c.description}
                      </span>
                      {c.severity && (
                        /* Severity — was 9px (unreadable), now 11px minimum */
                        <span className="font-mono text-[11px] uppercase tracking-[0.08em] font-semibold text-status-red shrink-0">
                          {c.severity}
                        </span>
                      )}
                      <ViewSourceLink onClick={() => openSource(c)} />
                    </div>
                    {/* Description — the most important text, give it room */}
                    {c.description && c.rule_name && (
                      <div className="ml-[6.5rem] mt-1.5 font-sans text-sm text-navy-1 leading-relaxed">
                        {c.description}
                      </div>
                    )}
                    <div className="ml-[6.5rem] mt-1.5 font-mono text-xs text-muted flex items-baseline gap-3 flex-wrap">
                      <span>LC: {c.lc_value ?? '—'} · INV: {c.presented_value ?? '—'}</span>
                      {refs && (
                        <span className="text-status-red">{refs}</span>
                      )}
                    </div>
                  </li>
                );
              })}
            </ul>
          </Section>
        )}

        {/* Unable to verify — expanded by default; same severity tier as
             FAIL in the sense that a human still has to act, just less
             critical, so the rows surface the description inline. */}
        {unverified.length > 0 && (
          <Section title="Unable to verify">
            <ul className="space-y-3">
              {unverified.map((c) => {
                const hasReason = c.description && c.description !== c.rule_name;
                return (
                  <li key={c.rule_id} className="flex flex-col gap-1">
                    <div className="flex items-baseline gap-3 text-sm">
                      <span className="font-mono text-xs text-muted shrink-0 w-24">
                        {c.rule_id}
                      </span>
                      <span className="font-sans text-sm font-medium text-navy-1 flex-1">
                        {c.rule_name ?? c.rule_id}
                      </span>
                      <ViewSourceLink onClick={() => openSource(c)} />
                    </div>
                    {hasReason && (
                      <div className="ml-[6.5rem] font-sans text-sm text-muted leading-relaxed">
                        {c.description}
                      </div>
                    )}
                  </li>
                );
              })}
            </ul>
          </Section>
        )}

        {/* Passed checks (collapsed) */}
        {passes.length > 0 && (
          <details className="border-t border-line">
            <summary className="px-6 py-3 cursor-pointer text-sm hover:bg-slate2 select-none flex items-center gap-3">
              <span className="font-sans text-[11px] uppercase tracking-[0.10em] font-semibold text-muted">
                Passed checks
              </span>
              <span className="font-mono font-semibold text-status-green">{passes.length}</span>
            </summary>
            <div className="px-6 pb-4">
              <ul className="grid grid-cols-2 gap-x-4 gap-y-1">
                {passes.map((c) => (
                  <li
                    key={c.rule_id}
                    className="flex items-baseline gap-2 text-xs"
                  >
                    <span className="text-status-green font-semibold">✓</span>
                    <span className="font-mono text-muted">{c.rule_id}</span>
                    <span className="font-sans text-navy-1 truncate flex-1">{c.rule_name}</span>
                    <ViewSourceLink onClick={() => openSource(c)} />
                  </li>
                ))}
              </ul>
            </div>
          </details>
        )}

        {/* Not required (collapsed) — audit-trail completeness; reviewer
             expands only if curious why a rule was skipped. Single column
             so the description (if any) can sit under each row without
             cramping. */}
        {notReq.length > 0 && (
          <details className="border-t border-line">
            <summary className="px-6 py-3 cursor-pointer text-sm hover:bg-slate2 select-none flex items-center gap-3">
              <span className="font-sans text-[11px] uppercase tracking-[0.10em] font-semibold text-muted">
                Not required
              </span>
              <span className="font-mono font-semibold text-muted">{notReq.length}</span>
            </summary>
            <div className="px-6 pb-4">
              <ul className="space-y-2">
                {notReq.map((c) => {
                  const hasReason = c.description && c.description !== c.rule_name;
                  return (
                    <li key={c.rule_id} className="flex flex-col gap-0.5">
                      <div className="flex items-baseline gap-2 text-xs">
                        <span className="text-muted">·</span>
                        <span className="font-mono text-muted shrink-0 w-24">{c.rule_id}</span>
                        <span className="font-sans text-navy-1 flex-1 truncate">
                          {c.rule_name ?? c.rule_id}
                        </span>
                        <ViewSourceLink onClick={() => openSource(c)} />
                      </div>
                      {hasReason && (
                        <div className="ml-[6.5rem] font-sans text-[11px] text-muted leading-relaxed">
                          {c.description}
                        </div>
                      )}
                    </li>
                  );
                })}
              </ul>
            </div>
          </details>
        )}

        {/* Officer's note */}
        <Section title="Officer's note">
          <textarea
            value={note}
            onChange={(e) => setNote(e.target.value)}
            placeholder="Add reviewer commentary (kept local — V1 does not persist this)…"
            rows={4}
            className="w-full text-sm px-3 py-2.5 rounded border border-line bg-paper focus:border-teal-2 focus:outline-none font-sans text-navy-1 placeholder:text-muted"
          />
        </Section>

        {/* Decision */}
        <Section title="Decision">
          <div className="flex flex-wrap items-center gap-4">
            <DecisionRadio value="approve" current={decision} onPick={setDecision} label="Approve"           tone="green" />
            <DecisionRadio value="amend"   current={decision} onPick={setDecision} label="Request amendment" tone="gold"  />
            <DecisionRadio value="reject"  current={decision} onPick={setDecision} label="Reject"            tone="red"   />
            <div className="flex-1" />
            <div className="font-mono text-xs text-muted">
              Reviewed: __________________ · {new Date().toISOString().slice(0, 10)}
            </div>
          </div>
        </Section>
      </div>

      <SourceDrawer
        target={drawerTarget}
        sessionId={sessionId}
        lc={lc}
        invoice={invoice}
        onClose={() => setDrawerTarget(null)}
      />
    </div>
  );
}

// ─── helpers ────────────────────────────────────────────────────────────────

function ViewSourceLink({ onClick }: { onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className="shrink-0 font-mono text-[10px] uppercase tracking-[0.08em] px-1.5 py-0.5 rounded-sm border border-line text-muted hover:border-teal-1/60 hover:text-teal-1 transition-colors"
      title="Open original LC + invoice with this rule's evidence highlighted"
    >
      View source
    </button>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="px-6 py-5 border-t border-line">
      {/* Section label — 11px sans, not mono; less aggressive tracking */}
      <div className="font-sans text-[11px] uppercase tracking-[0.10em] font-semibold text-muted mb-3">
        {title}
      </div>
      {children}
    </div>
  );
}

function SummaryCard({ title, body }: { title: string; body: React.ReactNode }) {
  return (
    <div className="bg-paper p-5">
      <div className="font-sans text-[11px] uppercase tracking-[0.10em] font-semibold text-muted mb-3">
        {title}
      </div>
      <div className="space-y-2 text-sm">{body}</div>
    </div>
  );
}

function lcSummary(lc: LcDocument | undefined): React.ReactNode {
  if (!lc) return <span className="text-muted italic">unavailable</span>;
  const env = lc.envelope;
  const currency = strField(env, 'credit_currency');
  const amount = numField(env, 'credit_amount');
  const tolP = intField(env, 'tolerance_plus');
  const tolM = intField(env, 'tolerance_minus');
  const expiryDate = strField(env, 'expiry_date');
  const expiryPlace = strField(env, 'expiry_place');
  const portLoad = strField(env, 'port_of_loading');
  const portDisch = strField(env, 'port_of_discharge');
  return (
    <>
      <KV label="LC No."            value={strField(env, 'lc_number')} />
      <KV label="Amount"            value={currency && amount != null ? `${currency} ${fmtNum(amount)} (+${tolP}/-${tolM}%)` : null} />
      <KV label="Beneficiary"       value={strField(env, 'beneficiary_name')} />
      <KV label="Applicant"         value={strField(env, 'applicant_name')} />
      <KV label="Expiry"            value={expiryDate && expiryPlace ? `${expiryDate} ${expiryPlace}` : expiryDate} />
      <KV label="Latest shipment"   value={strField(env, 'latest_shipment_date')} />
      <KV label="Loading/Discharge" value={portLoad && portDisch ? `${portLoad} → ${portDisch}` : null} />
    </>
  );
}

function invoiceSummary(inv: InvoiceDocument | undefined): React.ReactNode {
  if (!inv) return <span className="text-muted italic">unavailable</span>;
  const env = inv.envelope;
  const goods = strField(env, 'goods_description');
  const total = numField(env, 'credit_amount');
  const currency = strField(env, 'credit_currency');
  return (
    <>
      <KV label="Invoice No."  value={strField(env, 'invoice_number')} />
      <KV label="Issued"       value={strField(env, 'invoice_date')} />
      <KV label="Seller"       value={strField(env, 'beneficiary_name')} />
      <KV label="Buyer"        value={strField(env, 'applicant_name')} />
      <KV label="Goods"        value={goods ? goods.length > 60 ? goods.slice(0, 57) + '…' : goods : null} />
      <KV label="Total"        value={total != null && currency ? `${currency} ${fmtNum(total)}` : null} />
      <KV label="Trade terms"  value={strField(env, 'incoterms')} />
      <KV label="LC ref on inv" value={strField(env, 'lc_number')} />
    </>
  );
}

function KV({ label, value }: { label: string; value: string | null | undefined }) {
  return (
    <div className="flex items-baseline gap-3">
      {/* Label — 12px (text-xs) minimum, solid muted colour, no opacity reduction */}
      <span className="font-sans text-xs text-muted w-32 shrink-0">{label}</span>
      <span className="font-sans text-sm text-navy-1 break-words min-w-0">
        {value ?? <em className="text-muted italic not-italic">—</em>}
      </span>
    </div>
  );
}

function DecisionRadio({
  value,
  current,
  onPick,
  label,
  tone,
}: {
  value: Decision;
  current: Decision;
  onPick: (d: Decision) => void;
  label: string;
  tone: 'green' | 'gold' | 'red';
}) {
  const on = current === value;
  const toneCls: Record<string, string> = {
    green: on ? 'bg-status-greenSoft border-status-green text-status-green font-semibold' : '',
    gold:  on ? 'bg-status-goldSoft  border-status-gold  text-status-gold  font-semibold' : '',
    red:   on ? 'bg-status-redSoft   border-status-red   text-status-red   font-semibold' : '',
  };
  return (
    <button
      onClick={() => onPick(value)}
      className={[
        'px-4 py-2 rounded-btn text-sm border transition',
        on ? toneCls[tone] : 'border-line text-navy-1 hover:border-teal-2',
      ].join(' ')}
    >
      {on ? '●' : '○'} {label}
    </button>
  );
}

function fmtNum(v: string | number) {
  const n = typeof v === 'number' ? v : Number(v);
  if (Number.isNaN(n)) return String(v);
  return n.toLocaleString('en-US', { minimumFractionDigits: 2 });
}
