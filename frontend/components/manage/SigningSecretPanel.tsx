"use client";

import { useState } from "react";
import { api, ApiError } from "@/lib/api";
import type { SigningSecretDTO } from "@/lib/types";
import { Button, ConfirmDialog, Modal } from "@/components/ui";
import { SecretReveal } from "@/components/SecretReveal";

interface SigningSecretPanelProps {
  agentId: string;
  hasSigningSecret: boolean;
  /** Called once a secret exists, so the page can drop the "no secret" prompt. */
  onIssued: () => void;
}

/**
 * The agent's signing secret in the manage console. The value is never readable after it is issued,
 * so this only says whether one is set and offers rotation; a rotated secret is revealed once.
 * An agent registered before secrets existed gets a prompt to issue its first one.
 */
export function SigningSecretPanel({ agentId, hasSigningSecret, onIssued }: SigningSecretPanelProps) {
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [revealed, setRevealed] = useState<string | null>(null);

  async function rotate() {
    setBusy(true);
    setError(null);
    try {
      const res = await api<SigningSecretDTO>(`/agents/${agentId}/rotate-secret`, { method: "POST" });
      setConfirming(false);
      setRevealed(res.signingSecret);
      onIssued();
    } catch (e) {
      setConfirming(false);
      setError(e instanceof ApiError ? e.message : "Couldn't issue a new secret. Try again.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-labelledby="signing-secret-heading">
      {hasSigningSecret ? (
        <div className="flex flex-wrap items-center gap-3 rounded-md border border-line px-4 py-3">
          <div className="flex-1 text-sm">
            <h2 id="signing-secret-heading" className="font-medium text-fg">Signing secret</h2>
            <p className="text-xs text-muted">Set. It was shown once when issued; rotate it if it leaks.</p>
          </div>
          <Button type="button" variant="secondary" disabled={busy} onClick={() => setConfirming(true)}>
            Rotate secret
          </Button>
        </div>
      ) : (
        <div className="flex flex-wrap items-center gap-3 rounded-md border border-amber/30 bg-amber/10 px-4 py-3">
          <div className="flex-1 text-sm">
            <h2 id="signing-secret-heading" className="font-medium text-amber">No signing secret yet</h2>
            <p className="text-xs text-muted">
              This agent was registered before signing secrets existed. Get one now so your agent can
              verify the work HireAI sends it and sign its results.
            </p>
          </div>
          <Button type="button" disabled={busy} onClick={rotate}>
            {busy ? "Issuing…" : "Get a signing secret"}
          </Button>
        </div>
      )}
      {error && (
        <p role="alert" className="mt-2 text-xs text-red">
          {error}
        </p>
      )}

      <ConfirmDialog
        open={confirming}
        title="Rotate the signing secret?"
        confirmLabel="Rotate secret"
        danger
        busy={busy}
        onConfirm={rotate}
        onCancel={() => setConfirming(false)}
      >
        A new secret is issued and the current one stops working at once. Update your agent&apos;s
        server with the new secret straight away.
      </ConfirmDialog>

      <Modal open={revealed !== null} onClose={() => setRevealed(null)} ariaLabel="Your new signing secret">
        <div className="space-y-5 p-6">
          <h2 className="text-xl font-bold tracking-tight">New signing secret</h2>
          {revealed && <SecretReveal secret={revealed} />}
          <div className="flex justify-end">
            <Button type="button" variant="ghost" onClick={() => setRevealed(null)}>
              Done
            </Button>
          </div>
        </div>
      </Modal>
    </section>
  );
}
