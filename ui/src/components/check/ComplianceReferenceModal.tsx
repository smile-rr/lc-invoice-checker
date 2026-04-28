/**
 * Compliance Reference modal — Invoice Field Index.
 *
 * Displays the authoritative F01–F17 field coverage table sourced from
 * INVOICE_FIELDS. Each UCP/ISBP reference chip is a CitationPopover that
 * shows the full article excerpt inline.
 */
import { useEffect } from 'react';
import { INVOICE_FIELDS } from '../../data/invoiceFields';
import { UCP_ISBP_EXCERPTS } from '../../data/ucpIsbpExcerpts';
import { CitationPopover } from './CitationPopover';

interface Props {
  onClose: () => void;
}

const TYPE_TAG: Record<string, { label: string; cls: string }> = {
  PROGRAMMATIC: { label: 'PROG',   cls: 'bg-teal-1/15 text-teal-2 border-teal-2/40' },
  AGENT:       { label: 'AGENT',  cls: 'bg-navy-1/10 text-navy-1 border-navy-1/40' },
  'AGENT+TOOL':{ label: 'AGENT+',cls: 'bg-purple-50 text-purple-700 border-purple-200' },
  MIXED:       { label: 'MIXED',  cls: 'bg-slate2 text-muted border-line' },
  EMBEDDED:    { label: 'EMBD',   cls: 'bg-teal-1/10 text-teal-2 border-teal-2/30' },
  NOT_INCLUDED:{ label: '—',      cls: 'bg-slate2 text-muted border-line' },
};

export function ComplianceReferenceModal({ onClose }: Props) {
  const enabled = INVOICE_FIELDS.filter(
    (f) => f.type !== 'NOT_INCLUDED' && f.type !== 'EMBEDDED',
  );
  const notIncluded = INVOICE_FIELDS.filter((f) => f.type === 'NOT_INCLUDED');

  // Close CitationPopovers on Escape
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [onClose]);

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center px-4 pt-12 pb-12 overflow-y-auto">
      {/* Backdrop */}
      <div className="fixed inset-0 bg-black/40" onClick={onClose} />

      {/* Modal */}
      <div className="relative z-10 w-full max-w-4xl bg-paper rounded-card shadow-2xl border border-line flex flex-col max-h-[calc(100vh-96px)]">

        {/* Header */}
        <div className="px-6 py-4 border-b border-line shrink-0">
          <div className="flex items-start justify-between gap-4">
            <div>
              <h2 className="font-serif text-lg text-navy-1">Compliance Reference</h2>
              <p className="text-xs text-muted mt-0.5">
                UCP 600 &amp; ISBP 821 — Invoice Field Index &nbsp;
                <span className="text-muted/60">
                  ({enabled.length} active · {notIncluded.length} not included · 1 embedded)
                </span>
              </p>
            </div>
            <button
              onClick={onClose}
              className="shrink-0 text-muted hover:text-navy-1 px-2 py-1 text-xs font-mono uppercase tracking-widest"
            >
              ✕ Close
            </button>
          </div>
        </div>

        {/* Table */}
        <div className="overflow-y-auto flex-1 px-6 py-4">
          <table className="w-full text-[11px] border border-line rounded-card overflow-hidden">
            <thead>
              <tr className="bg-slate2 border-b border-line">
                <th className="px-3 py-2 text-left font-mono font-bold text-muted uppercase tracking-widest w-10">ID</th>
                <th className="px-3 py-2 text-left font-mono font-bold text-muted uppercase tracking-widest">Field Name</th>
                <th className="px-3 py-2 text-left font-mono font-bold text-muted uppercase tracking-widest w-16 hidden md:table-cell">Short</th>
                <th className="px-3 py-2 text-left font-mono font-bold text-muted uppercase tracking-widest w-40">UCP / ISBP Ref</th>
                <th className="px-3 py-2 text-left font-mono font-bold text-muted uppercase tracking-widest">Covering Rules</th>
                <th className="px-3 py-2 text-center font-mono font-bold text-muted uppercase tracking-widest w-16">Type</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {INVOICE_FIELDS.map((field) => {
                const tag = TYPE_TAG[field.type ?? 'NOT_INCLUDED'];
                const isSpecial = field.type === 'NOT_INCLUDED' || field.type === 'EMBEDDED';
                return (
                  <tr
                    key={field.id}
                    className={isSpecial ? 'bg-slate2/30' : 'hover:bg-slate2/30 transition-colors'}
                  >
                    {/* ID */}
                    <td className="px-3 py-2.5 font-mono font-bold text-navy-1">{field.id}</td>

                    {/* Field Name */}
                    <td className="px-3 py-2.5 font-sans font-medium text-navy-1">
                      {field.fieldName}
                    </td>

                    {/* Short */}
                    <td className="px-3 py-2.5 font-mono text-muted hidden md:table-cell">
                      {field.shortName}
                    </td>

                    {/* UCP / ISBP Ref — CitationPopover chips */}
                    <td className="px-3 py-2.5">
                      <RefChips refs={field.refs} />
                    </td>

                    {/* Covering Rules */}
                    <td className="px-3 py-2.5">
                      {field.type === 'NOT_INCLUDED' ? (
                        <span className="font-sans text-muted/70 italic text-[11px]">
                          {field.id === 'F12' && 'not included — needs B/L shipment date'}
                          {field.id === 'F13' && 'not included — needs cross-document input'}
                          {field.id === 'F17' && 'not included — needs insurance document'}
                        </span>
                      ) : field.type === 'EMBEDDED' ? (
                        <span className="font-sans text-teal-2/80 text-[11px]">
                          ✅ embedded in all rules
                        </span>
                      ) : (
                        <span className="font-mono text-navy-1 text-[11px]">
                          {field.coveringRules.join(' · ')}
                        </span>
                      )}
                    </td>

                    {/* Type */}
                    <td className="px-3 py-2.5 text-center">
                      <span
                        className={[
                          'inline-block font-mono text-[10px] uppercase tracking-[0.06em] font-bold',
                          'px-1.5 py-0.5 rounded-sm border',
                          tag.cls,
                        ].join(' ')}
                      >
                        {tag.label}
                      </span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>

          {/* Footer */}
          <p className="text-xs text-muted/60 mt-4">
            Source:{' '}
            <span className="font-mono">rules/catalog.yml</span>
            {' · '}
            <span className="font-mono">docs/refer-doc/ucp600_isbp821_invoice_rules.md</span>
            {' · '}
            ICC Publication No. 600 (2007) · ICC Publication No. 821E (2013)
          </p>
        </div>
      </div>
    </div>
  );
}

// ─── Ref chips — CitationPopover per reference ─────────────────────────────────

/** Parses "UCP 18(a)  ISBP C6" into CitationPopover chips. */
function RefChips({ refs }: { refs: string }) {
  return (
    <div className="flex flex-wrap gap-1">
      {refs.split('  ').map((r) => {
        const ref = r.trim();
        if (!ref) return null;
        const excerpt = UCP_ISBP_EXCERPTS[ref] ?? null;
        return (
          <CitationPopover
            key={ref}
            reference={ref}
            excerpt={excerpt}
          />
        );
      })}
    </div>
  );
}
