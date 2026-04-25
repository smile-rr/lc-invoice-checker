import { useCallback, useState } from 'react';
import { createElement, type ReactElement } from 'react';
import { ConfirmDialog } from '../components/shell/ConfirmDialog';

export interface ConfirmOpts {
  title: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  tone?: 'primary' | 'danger';
}

interface PendingConfirm extends ConfirmOpts {
  resolve: (v: boolean) => void;
}

/**
 * Promise-based imperative confirm. Caller renders {@code Dialog} once anywhere
 * in their tree; calls to {@code confirm(opts)} return a {@link Promise} that
 * resolves {@code true} on Confirm and {@code false} on Cancel/Esc/backdrop.
 *
 * Usage:
 * <pre>
 *   const { confirm, Dialog } = useConfirm();
 *   const ok = await confirm({ title: '…', message: '…' });
 *   return (&lt;&gt; ... {Dialog} &lt;/&gt;);
 * </pre>
 */
export function useConfirm(): {
  confirm: (opts: ConfirmOpts) => Promise<boolean>;
  Dialog: ReactElement | null;
} {
  const [pending, setPending] = useState<PendingConfirm | null>(null);

  const confirm = useCallback(
    (opts: ConfirmOpts) =>
      new Promise<boolean>((resolve) => {
        setPending({ ...opts, resolve });
      }),
    [],
  );

  const Dialog: ReactElement | null = pending
    ? createElement(ConfirmDialog, {
        open: true,
        title: pending.title,
        message: pending.message,
        confirmLabel: pending.confirmLabel,
        cancelLabel: pending.cancelLabel,
        tone: pending.tone,
        onConfirm: () => {
          pending.resolve(true);
          setPending(null);
        },
        onCancel: () => {
          pending.resolve(false);
          setPending(null);
        },
      })
    : null;

  return { confirm, Dialog };
}
