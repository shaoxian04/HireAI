"use client";

import { useState } from "react";
import { api, ApiError } from "@/lib/api";
import { Card, Button } from "@/components/ui";
import type { DisputeOutcomeDTO, RulingCategory } from "@/lib/types";

const CATEGORY_LABEL: Record<RulingCategory, string> = {
  FULFILLED: "Fulfilled — pay the builder",
  PARTIALLY_FULFILLED: "Partially fulfilled — split",
  NOT_FULFILLED: "Not fulfilled — full refund",
};

/**
 * Client-facing dispute lifecycle panel: ARBITRATING (under review) -> RULED (proposed ruling,
 * accept or appeal to a human administrator) -> ESCALATED (awaiting admin) -> RESOLVED (final).
 * Distinct from the read-only DisputeOutcomePanel (builder earnings), which never offers actions.
 */
export function DisputeProgressPanel({
  outcome,
  onChange,
}: {
  outcome: DisputeOutcomeDTO;
  onChange: (next: DisputeOutcomeDTO) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const proposal = outcome.rulings[outcome.rulings.length - 1];

  async function act(kind: "accept-ruling" | "appeal") {
    setBusy(true);
    setError(null);
    try {
      const next = await api<DisputeOutcomeDTO>(`/disputes/${outcome.disputeId}/${kind}`, { method: "POST" });
      onChange(next);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Action failed");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="space-y-4 border-t border-line">
      <p className="eyebrow">Dispute</p>

      {(outcome.status === "OPEN" || outcome.status === "ARBITRATING") && (
        <p className="flex items-center gap-2 font-mono text-xs uppercase tracking-wider text-cyan">
          <span className="size-1.5 rounded-full bg-cyan dot-live" /> Under review by an arbitrator…
        </p>
      )}

      {outcome.status === "RULED" && proposal && (
        <div className="space-y-3">
          <p className="eyebrow text-dim">Proposed ruling · arbitrator</p>
          <p className="font-mono text-sm text-fg">{CATEGORY_LABEL[proposal.category]}</p>
          {proposal.rationale && <p className="text-sm leading-relaxed text-muted">{proposal.rationale}</p>}
          <p className="text-sm text-muted">
            Escrow is still held. Accept this, or appeal to a human administrator for a final decision.
          </p>
          {error && <p role="alert" className="font-mono text-xs text-red">{error}</p>}
          <div className="flex gap-2">
            <Button onClick={() => act("accept-ruling")} disabled={busy}>Accept ruling ▸</Button>
            <Button variant="ghost" onClick={() => act("appeal")} disabled={busy}>Appeal to a human</Button>
          </div>
        </div>
      )}

      {outcome.status === "ESCALATED" && (
        <p className="font-mono text-xs text-amber">Escalated to a human administrator for final review…</p>
      )}

      {outcome.status === "RESOLVED" && (
        <div className="space-y-2">
          <p className="eyebrow text-dim">Resolved</p>
          {outcome.effectiveCategory && (
            <p className="font-mono text-sm text-fg">{CATEGORY_LABEL[outcome.effectiveCategory]}</p>
          )}
          {outcome.rulings.map((r, i) => (
            <p key={i} className="text-sm text-muted">
              <span className="text-accent">{r.decidedBy === "ADMINISTRATOR" ? "Administrator" : "Arbitrator"}</span>
              {r.rationale ? ` — ${r.rationale}` : ""}
            </p>
          ))}
        </div>
      )}
    </Card>
  );
}
