"use client";

import type { ReactNode } from "react";
import { Button } from "./Button";
import { Modal } from "./Modal";

interface ConfirmDialogProps {
  open: boolean;
  title: string;
  /** What will happen, and whether it can be undone. */
  children: ReactNode;
  /** Names the action exactly, e.g. "Deactivate agent" — never "OK". */
  confirmLabel: string;
  danger?: boolean;
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

/**
 * Asks before an action that's hard or impossible to undo. Focus starts on the dialog itself (the
 * Modal does that), so a stray Enter never confirms; Esc and the backdrop cancel.
 */
export function ConfirmDialog({ open, title, children, confirmLabel, danger, busy, onConfirm, onCancel }: ConfirmDialogProps) {
  return (
    <Modal open={open} onClose={onCancel} ariaLabel={title} size="sm">
      <div className="space-y-3 p-6">
        <h2 className="text-lg font-bold text-fg">{title}</h2>
        <div className="text-sm leading-relaxed text-muted">{children}</div>
        <div className="flex justify-end gap-2 pt-3">
          <Button type="button" variant="ghost" onClick={onCancel}>
            Cancel
          </Button>
          <Button type="button" variant={danger ? "danger" : "primary"} disabled={busy} onClick={onConfirm}>
            {busy ? "Working…" : confirmLabel}
          </Button>
        </div>
      </div>
    </Modal>
  );
}
