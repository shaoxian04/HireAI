import { Card } from "@/components/ui/Card";
import type { DisputeOutcomeDTO, RulingCategory } from "@/lib/types";

const CATEGORY_LABEL: Record<RulingCategory, string> = {
  FULFILLED: "Fulfilled — ruled in the agent's favour",
  PARTIALLY_FULFILLED: "Partially fulfilled — split settlement",
  NOT_FULFILLED: "Not fulfilled — full refund",
};

export function DisputeOutcomePanel({ outcome }: { outcome: DisputeOutcomeDTO }) {
  if (!outcome.rulings.length) return null;
  const effective = outcome.effectiveCategory;
  // When every ruling is a platform FALLBACK, the auto-resolve note is self-explanatory;
  // skip the category label to avoid duplicating "full refund" in the UI.
  const allFallback = outcome.rulings.every((r) => r.decidedBy === "FALLBACK");

  return (
    <Card className="space-y-4 border-t border-line">
      <p className="eyebrow">Arbitration outcome</p>
      {effective && !allFallback && (
        <p className="font-mono text-sm text-fg">{CATEGORY_LABEL[effective]}</p>
      )}
      <ul className="space-y-3">
        {outcome.rulings.map((r, i) => (
          <li key={i} className="space-y-1">
            {r.decidedBy === "FALLBACK" ? (
              <p className="font-mono text-xs text-amber">
                Auto-resolved (arbitrator unavailable) — full refund.
              </p>
            ) : (
              <p className="eyebrow text-dim">
                {r.decidedBy === "ADMINISTRATOR" ? "Administrator override" : "Arbitrator"} · tier {r.tier}
              </p>
            )}
            {r.rationale && (
              <p className="text-sm leading-relaxed text-muted">{r.rationale}</p>
            )}
          </li>
        ))}
      </ul>
    </Card>
  );
}
