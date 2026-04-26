import { useState } from 'react';
import type { CheckTrace } from '../../types';

interface Props {
  trace: CheckTrace | undefined;
}

/**
 * Collapsible audit pane for LLM rule cards. Closed by default — the verdict
 * cell already answers "what did the model say". This pane answers "show me
 * the prompt and the raw response" for the auditor who must verify provenance
 * before relying on a model finding.
 */
export function LlmAuditPane({ trace }: Props) {
  const [open, setOpen] = useState(false);
  const llm = trace?.llm_trace ?? null;
  return (
    <div className="px-4 py-2 border-t border-line bg-slate2">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex items-center gap-2 w-full font-sans text-[11px] uppercase tracking-[0.10em] text-muted hover:text-navy-1 transition-colors"
      >
        <span aria-hidden>{open ? '▾' : '▸'}</span>
        <span className="font-semibold">LLM audit</span>
        {llm && (
          <span className="ml-2 text-navy-1 normal-case tracking-normal">
            {llm.model ?? 'model unknown'} · {llm.latency_ms}ms
            {llm.tokens_in !== undefined && llm.tokens_out !== undefined && (
              <> · {llm.tokens_in}+{llm.tokens_out} tok</>
            )}
          </span>
        )}
        <span className="flex-1" />
        {!llm && trace && (
          <span className="text-muted normal-case tracking-normal italic">
            no LLM call recorded
          </span>
        )}
        {!trace && (
          <span className="text-muted normal-case tracking-normal italic">
            audit data pending
          </span>
        )}
      </button>
      {open && (
        <div className="mt-2 space-y-2 animate-fadein">
          {!trace && (
            <div className="font-sans text-xs text-muted italic">
              Refresh once the session is persisted — the /trace hydrate populates
              prompt and response for the audit pane.
            </div>
          )}
          {trace && !llm && (
            <div className="font-sans text-xs text-muted italic">
              No LLM call recorded for this rule (the strategy may have failed
              before invoking the model).
            </div>
          )}
          {llm && (
            <>
              <Block label="Prompt" body={llm.prompt_rendered} />
              <Block label="Raw response" body={llm.raw_response} />
              {llm.parsed_response !== undefined && llm.parsed_response !== null && (
                <Block
                  label="Parsed JSON"
                  body={typeof llm.parsed_response === 'string'
                    ? llm.parsed_response
                    : JSON.stringify(llm.parsed_response, null, 2)}
                />
              )}
              {llm.error && <Block label="Error" body={llm.error} tone="error" />}
            </>
          )}
        </div>
      )}
    </div>
  );
}

function Block({
  label,
  body,
  tone,
}: {
  label: string;
  body: string;
  tone?: 'error';
}) {
  return (
    <div>
      <div className="font-sans text-[11px] uppercase tracking-[0.10em] text-muted mb-1">
        {label}
      </div>
      <pre
        className={[
          'font-mono text-[11px] leading-relaxed bg-paper border border-line',
          'px-3 py-2 max-h-60 overflow-auto whitespace-pre-wrap break-words',
          tone === 'error' ? 'text-status-red' : 'text-navy-1',
        ].join(' ')}
      >
        {body}
      </pre>
    </div>
  );
}
