"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { RoleGuard } from "@/components/RoleGuard";
import { useAuth } from "@/lib/auth";
import { ApiError } from "@/lib/api";
import { Button, Card } from "@/components/ui";

function BecomeBuilderInner() {
  const { becomeBuilder } = useAuth();
  const router = useRouter();
  const [accepted, setAccepted] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onUpgrade() {
    setError(null);
    setBusy(true);
    try {
      await becomeBuilder();
      router.replace("/builder");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Upgrade failed");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mx-auto max-w-xl px-5 py-12">
      <p className="eyebrow flex items-center gap-2">
        <span className="inline-block h-px w-6 bg-accent" />
        Upgrade
      </p>
      <h1 className="mt-3 text-3xl font-extrabold tracking-tight">Become a builder</h1>
      <p className="mt-2 text-sm text-muted">
        Register AI agents, run a storefront, and earn credits on accepted work. Your client account
        and wallet stay exactly as they are — you gain the builder surface on top.
      </p>

      <Card className="mt-6 p-6">
        <label className="flex items-start gap-3 text-sm text-fg">
          <input
            type="checkbox"
            aria-label="accept builder terms"
            checked={accepted}
            onChange={(e) => setAccepted(e.target.checked)}
            className="mt-1"
          />
          <span>
            I agree to the builder terms: my agents must honour their declared output spec, and
            payouts are settled per the platform&apos;s escrow rules.
          </span>
        </label>

        {error && (
          <p role="alert" className="mt-4 rounded-md border border-red/30 bg-red/10 px-3 py-2 font-mono text-xs text-red">
            {error}
          </p>
        )}

        <Button onClick={onUpgrade} disabled={!accepted || busy} className="mt-6 w-full">
          {busy ? "Upgrading…" : "Become a builder ▸"}
        </Button>
      </Card>
    </div>
  );
}

export default function BecomeBuilderPage() {
  return (
    <RoleGuard role="CLIENT">
      <BecomeBuilderInner />
    </RoleGuard>
  );
}
