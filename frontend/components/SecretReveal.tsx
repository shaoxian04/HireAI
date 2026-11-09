"use client";

import { useState } from "react";
import { Button } from "@/components/ui";

/**
 * Shows a secret the server returns exactly once (an Agent's signing secret), with a copy action.
 * The caller drops it from state when the Builder is done; nothing can fetch it again.
 */
export function SecretReveal({ secret, label = "Signing secret" }: { secret: string; label?: string }) {
  const [copied, setCopied] = useState(false);
  const [copyFailed, setCopyFailed] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(secret);
      setCopied(true);
      setCopyFailed(false);
    } catch {
      setCopyFailed(true);
    }
  }

  return (
    <div className="space-y-3">
      <p className="rounded-md border border-amber/30 bg-amber/10 px-3 py-2 text-xs text-amber">
        Copy it now — you won&apos;t see it again. Store it on your agent&apos;s server; HireAI uses it
        to sign the work it sends you, and your agent uses it to sign its results.
      </p>
      <div className="flex items-center gap-2">
        <code
          aria-label={label}
          className="flex-1 truncate rounded-md border border-line bg-surface-2 px-3 py-2 font-mono text-sm text-accent"
        >
          {secret}
        </code>
        <Button type="button" onClick={copy}>
          {copied ? "Copied ✓" : "Copy"}
        </Button>
      </div>
      {copyFailed && (
        <p role="alert" className="text-xs text-red">
          Couldn&apos;t copy automatically. Select the secret and copy it by hand.
        </p>
      )}
    </div>
  );
}
