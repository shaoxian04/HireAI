"use client";

import { useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";
import type { ContractDTO, EngagementDTO } from "@/lib/types";
import { Badge, Button, ConfirmDialog } from "@/components/ui";

const STATUS_TEXT: Record<EngagementDTO["status"], string> = {
  AWAITING_AGENT_ACCEPTANCE: "Waiting for you",
  ACTIVE: "Active",
  ENDED: "Ended",
};

/** Colours borrowed from the shared status palette: amber waits, green is live, dim is over. */
const BADGE_STATUS: Record<EngagementDTO["status"], string> = {
  AWAITING_AGENT_ACCEPTANCE: "AWAITING_AGENT",
  ACTIVE: "ACTIVE",
  ENDED: "DEACTIVATED",
};

function terms(c: ContractDTO): string {
  const params = Object.entries(c.params ?? {}).map(([k, v]) => `${k} ${Array.isArray(v) ? v.join(", ") : String(v)}`);
  return `${c.template} v${c.version}${params.length ? ` · ${params.join(" · ")}` : ""}`;
}

/**
 * Engagements buyers opened with this agent (spec S4, D44): each records a buyer's consent to send the
 * agent confidential documents. One with a Template variant waits for the builder to accept those exact
 * terms; once accepted, bookings under them need no Offer coverage. Renders nothing without Engagements.
 */
export function EngagementsPanel({ agentId }: { agentId: string }) {
  const [engagements, setEngagements] = useState<EngagementDTO[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [accepting, setAccepting] = useState<EngagementDTO | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api<EngagementDTO[]>("/engagements/incoming")
      .then((all) => setEngagements(all.filter((e) => e.agentId === agentId)))
      .catch(() => setEngagements([]));
  }, [agentId]);

  async function accept(e: EngagementDTO) {
    setBusy(true);
    setError(null);
    try {
      const updated = await api<EngagementDTO>(`/engagements/${e.id}/accept`, { method: "POST" });
      setEngagements((list) => (list ?? []).map((x) => (x.id === updated.id ? updated : x)));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Couldn't accept the terms. Try again.");
    } finally {
      setBusy(false);
      setAccepting(null);
    }
  }

  if (!engagements || engagements.length === 0) return null;

  return (
    <section aria-labelledby="engagements-heading" className="rounded-md border border-line px-4 py-3">
      <h2 id="engagements-heading" className="text-sm font-medium text-fg">Engagements</h2>
      <p className="text-xs text-muted">
        Buyers who chose this agent and consented to send it confidential documents. Each document
        arrives in the WorkOrder as a per-Item link that expires at the delivery deadline; buyers see every download.
      </p>
      <ul className="mt-2 divide-y divide-line">
        {engagements.map((e) => (
          <li key={e.id} className="flex flex-wrap items-center gap-3 py-2 text-sm">
            <Badge status={BADGE_STATUS[e.status]}>{STATUS_TEXT[e.status]}</Badge>
            <span className="min-w-0 flex-1 text-xs text-muted">
              Since {new Date(e.consentedAt).toLocaleDateString()} · documents kept {e.retentionDays} days
              {e.templateVariant ? <> · terms <span className="font-mono">{terms(e.templateVariant)}</span></> : null}
            </span>
            {e.status === "AWAITING_AGENT_ACCEPTANCE" && (
              <Button type="button" size="sm" disabled={busy} onClick={() => setAccepting(e)}>Accept terms</Button>
            )}
          </li>
        ))}
      </ul>
      {error && <p role="alert" className="mt-2 text-xs text-red">{error}</p>}
      <ConfirmDialog
        open={accepting !== null}
        title="Accept these terms?"
        confirmLabel="Accept terms"
        busy={busy}
        onConfirm={() => accepting && void accept(accepting)}
        onCancel={() => setAccepting(null)}
      >
        Your agent will be held to{" "}
        <span className="font-mono">{accepting?.templateVariant ? terms(accepting.templateVariant) : ""}</span>{" "}
        on this buyer&apos;s tasks, even where your Offer does not cover them.
      </ConfirmDialog>
    </section>
  );
}
