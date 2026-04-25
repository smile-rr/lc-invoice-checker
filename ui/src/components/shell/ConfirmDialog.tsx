import { useEffect } from 'react';
import { createPortal } from 'react-dom';

interface Props {
  open: boolean;
  title: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  tone?: 'primary' | 'danger';
  onConfirm: () => void;
  onCancel: () => void;
}

/**
 * Modal confirm dialog that matches the design tokens (DM Serif title, DM Sans
 * body, navy/teal palette). Rendered via {@link createPortal} into
 * {@code document.body} so stacking contexts in the page can't clip it.
 *
 * Esc key and backdrop click both trigger {@link Props.onCancel} so dismissal
 * stays predictable. The confirm button takes focus on open for keyboard-first
 * operators.
 */
export function ConfirmDialog({
  open,
  title,
  message,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  tone = 'primary',
  onConfirm,
  onCancel,
}: Props) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancel();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onCancel]);

  if (!open) return null;

  const confirmCls =
    tone === 'danger'
      ? 'bg-status-red text-white hover:bg-status-red/90'
      : 'bg-navy-1 text-white hover:bg-navy-2';

  return createPortal(
    <div
      className="fixed inset-0 z-50 bg-navy-1/60 flex items-center justify-center p-4 animate-fadein"
      onClick={onCancel}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="confirm-dialog-title"
        className="bg-paper rounded-card border border-line shadow-xl max-w-md w-full p-6"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 id="confirm-dialog-title" className="font-serif text-xl text-navy-1">
          {title}
        </h2>
        <p className="text-sm text-navy-1 leading-relaxed whitespace-pre-line mt-2">
          {message}
        </p>
        <div className="mt-5 flex items-center justify-end gap-2">
          <button
            onClick={onCancel}
            className="text-sm text-muted hover:text-navy-1 px-3 py-2"
          >
            {cancelLabel}
          </button>
          <button
            autoFocus
            onClick={onConfirm}
            className={`text-sm font-medium px-4 py-2 rounded-btn ${confirmCls}`}
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  );
}
