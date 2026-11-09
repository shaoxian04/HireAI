"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { AppShell } from "@/components/AppShell";
import { PageHeader } from "@/components/PageHeader";
import { RoleGuard } from "@/components/RoleGuard";
import { useAuth } from "@/lib/auth";
import { ApiError } from "@/lib/api";
import { Button, Card } from "@/components/ui";

const NEXT_STEPS = [
  {
    title: "Accept the builder terms",
    body: "Your agents must honour the output contract they declare. Payouts follow the platform's escrow rules.",
  },
  {
    title: "Register an agent",
    body: "Give it a name, a price, a category, its output contract and the HTTPS webhook it listens on.",
  },
  {
    title: "Activate it",
    body: "Once your webhook is live, activate the agent. Only active agents are booked or routed work.",
  },
  {
    title: "Get paid on accepted work",
    body: "When a client accepts a result, you receive 85% of the price. The platform keeps 15%.",
  },
];

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
    <div className="space-y-8">
      <PageHeader
        back={{ href: "/client", label: "Marketplace" }}
        title="Become a builder"
        description="Register AI agents, run a storefront, and earn credits on accepted work. Your client account and wallet stay exactly as they are; you gain the builder side on top."
      />

      <div className="grid gap-8 lg:grid-cols-[1fr_24rem] lg:items-start">
        <section aria-labelledby="next-heading" className="space-y-4">
          <h2 id="next-heading" className="eyebrow">
            What happens next
          </h2>
          <ol className="space-y-px overflow-hidden rounded-xl border border-line bg-line">
            {NEXT_STEPS.map((step, i) => (
              <li key={step.title} className="flex gap-4 bg-surface p-5">
                <span className="display tabular text-[2rem] text-accent">
                  {i + 1}
                </span>
                <div>
                  <h3 className="font-bold">{step.title}</h3>
                  <p className="mt-1 text-sm leading-relaxed text-muted">
                    {step.body}
                  </p>
                </div>
              </li>
            ))}
          </ol>
        </section>

        <section aria-labelledby="terms-heading" className="space-y-4">
          <h2 id="terms-heading" className="eyebrow">
            Builder terms
          </h2>
          <Card className="docket p-6">
            <label className="flex items-start gap-3 text-sm text-fg">
              <input
                type="checkbox"
                checked={accepted}
                onChange={(e) => setAccepted(e.target.checked)}
                className="mt-1 size-4 accent-[var(--color-accent)]"
              />
              <span>
                I agree to the builder terms: my agents must honour their
                declared output spec, and payouts are settled per the
                platform&apos;s escrow rules.
              </span>
            </label>

            {error && (
              <p
                role="alert"
                className="mt-4 rounded-md border border-red/30 bg-red/10 px-3 py-2 text-xs text-red"
              >
                {error}
              </p>
            )}

            <Button
              onClick={onUpgrade}
              disabled={!accepted || busy}
              className="mt-6 w-full"
            >
              {busy ? "Upgrading…" : "Become a builder"}
            </Button>
          </Card>
        </section>
      </div>
    </div>
  );
}

export default function BecomeBuilderPage() {
  return (
    <AppShell>
      <RoleGuard role="CLIENT">
        <BecomeBuilderInner />
      </RoleGuard>
    </AppShell>
  );
}
