import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';

interface Props {
  /** Display label, e.g. "UCP 600 Art. 18(a)" or "ISBP 821 C5". */
  reference: string;
  /** Plain-English excerpt sourced from the rule catalog. */
  excerpt: string | null;
}

/** Width of the popover panel. Kept in one place so positioning agrees with CSS. */
const POPOVER_WIDTH = 360;
const VIEWPORT_PAD = 8;

/**
 * Inline citation chip that opens an anchored popover with the full excerpt
 * on click. Keeps reading inline (no modal) so users can still scan the rule
 * list while comparing the verdict against the source.
 *
 * Behaviour:
 *   - Click chip toggles the popover.
 *   - Click outside or Esc closes it.
 *   - Copy button copies "{reference}: {excerpt}" to the clipboard.
 *   - Falls back to a plain non-interactive label when no excerpt is available
 *     (so we never advertise an action we can't fulfil).
 */
export function CitationPopover({ reference, excerpt }: Props) {
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const popoverRef = useRef<HTMLDivElement>(null);
  // Fixed-position coordinates measured from the trigger's bounding rect.
  // Recomputed on open + on scroll/resize so the popover follows the chip.
  const [coords, setCoords] = useState<{ top: number; left: number } | null>(null);

  // Position the popover under the chip, but flip to align with the right
  // edge of the chip if it would otherwise overflow the right side of the
  // viewport. Same idea vertically — flip above the chip if there's no room
  // below.
  useLayoutEffect(() => {
    if (!open) return;
    function place() {
      const trig = triggerRef.current?.getBoundingClientRect();
      if (!trig) return;
      const popHeight = popoverRef.current?.offsetHeight ?? 220;
      const winW = window.innerWidth;
      const winH = window.innerHeight;
      // Horizontal: prefer left-aligned with the chip, but pull it left if
      // we'd run off the right edge.
      let left = trig.left;
      if (left + POPOVER_WIDTH + VIEWPORT_PAD > winW) {
        left = Math.max(VIEWPORT_PAD, winW - POPOVER_WIDTH - VIEWPORT_PAD);
      }
      // Vertical: prefer below the chip; flip above if too little space.
      let top = trig.bottom + 6;
      if (top + popHeight + VIEWPORT_PAD > winH && trig.top - popHeight - 6 > VIEWPORT_PAD) {
        top = trig.top - popHeight - 6;
      }
      setCoords({ top, left });
    }
    place();
    window.addEventListener('scroll', place, true);
    window.addEventListener('resize', place);
    return () => {
      window.removeEventListener('scroll', place, true);
      window.removeEventListener('resize', place);
    };
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    const onClick = (e: MouseEvent) => {
      const t = e.target as Node;
      const insideTrigger = triggerRef.current?.contains(t);
      const insidePopover = popoverRef.current?.contains(t);
      if (!insideTrigger && !insidePopover) setOpen(false);
    };
    window.addEventListener('keydown', onKey);
    window.addEventListener('mousedown', onClick);
    return () => {
      window.removeEventListener('keydown', onKey);
      window.removeEventListener('mousedown', onClick);
    };
  }, [open]);

  if (!excerpt) {
    // No excerpt available — render the reference as static text so the chip
    // doesn't masquerade as interactive.
    return <span className="font-mono text-[11px] text-muted">{reference}</span>;
  }

  // Render the popover into document.body via a portal so it escapes any
  // ancestor with overflow:hidden (the rule card's <article> uses it for
  // border/animation styling). Position is fixed, computed from the
  // trigger's bounding rect — see the useLayoutEffect above.
  const popover = open && coords ? createPortal(
    <div
      ref={popoverRef}
      role="dialog"
      aria-label={reference}
      className="fixed w-[360px] z-40 bg-paper text-navy-1 rounded-card border border-line shadow-xl animate-fadein"
      style={{ top: coords.top, left: coords.left }}
    >
      <div className="px-3 py-2 border-b border-line flex items-baseline justify-between gap-2">
        <span className="font-mono text-[11px] font-semibold text-navy-1">
          {reference}
        </span>
        <button
          type="button"
          onClick={() => setOpen(false)}
          className="text-muted hover:text-navy-1 text-xs leading-none p-1 -m-1"
          aria-label="Close"
        >
          ✕
        </button>
      </div>
      <div className="px-3 py-3 font-sans text-xs leading-relaxed text-navy-1">
        {excerpt}
      </div>
    </div>,
    document.body,
  ) : null;

  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        onClick={() => setOpen((v) => !v)}
        className={[
          'font-mono text-[11px] inline-flex items-center gap-1',
          'px-1.5 py-0.5 rounded-sm border transition-colors',
          open
            ? 'bg-teal-1/15 border-teal-2/60 text-teal-2'
            : 'border-line text-muted hover:border-teal-2/40 hover:text-navy-1 hover:bg-slate2/60',
        ].join(' ')}
        aria-haspopup="dialog"
        aria-expanded={open}
        title={`See the rule basis for ${reference}`}
      >
        <span>{reference}</span>
        <span aria-hidden className="text-[10px]">↗</span>
      </button>
      {popover}
    </>
  );
}
