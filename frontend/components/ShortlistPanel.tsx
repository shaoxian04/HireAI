"use client";

import type { AgentOptionDTO } from "@/lib/types";
import { Button, Card } from "@/components/ui";

interface Props {
  shortlist: AgentOptionDTO[];
  nearMisses: AgentOptionDTO[];
  budget: number;
  onSelect: (option: AgentOptionDTO) => void;
}

export function ShortlistPanel({ shortlist, nearMisses, budget, onSelect }: Props) {
  if (shortlist.length === 0 && nearMisses.length === 0) {
    return (
      <Card>
        <p className="font-mono text-sm text-dim">
          No agents match this category yet. Adjust the category or budget and search again.
        </p>
      </Card>
    );
  }
  return (
    <div className="space-y-6">
      {shortlist.length > 0 && (
        <section className="space-y-3">
          <p className="eyebrow">In budget</p>
          {shortlist.map((o) => (
            <AgentCard key={o.agentVersionId} option={o} onSelect={onSelect} />
          ))}
        </section>
      )}
      {nearMisses.length > 0 && (
        <section className="space-y-3">
          <p className="eyebrow">Above your budget</p>
          <p className="font-mono text-xs text-dim">
            These cost more than your {budget} cr budget — selecting one pays its price.
          </p>
          {nearMisses.map((o) => (
            <AgentCard key={o.agentVersionId} option={o} aboveBudget onSelect={onSelect} />
          ))}
        </section>
      )}
    </div>
  );
}

function AgentCard({
  option,
  aboveBudget,
  onSelect,
}: {
  option: AgentOptionDTO;
  aboveBudget?: boolean;
  onSelect: (o: AgentOptionDTO) => void;
}) {
  return (
    <Card>
      <div className="flex items-center justify-between gap-4">
        <div>
          <p className="font-semibold">{option.agentName}</p>
          {option.tagline && <p className="text-sm text-muted">{option.tagline}</p>}
          <p className="mt-1 font-mono text-xs text-dim">
            <span className="tabular text-accent">{option.price} cr</span>
            {" · ★ "}
            <span className="tabular">{option.reputationScore}</span>
            {" · "}
            {option.availability === "AVAILABLE" ? "available" : "busy"}
            {option.outputFormat && <> · {option.outputFormat}</>}
          </p>
        </div>
        <Button onClick={() => onSelect(option)}>
          {aboveBudget ? "Select (above budget)" : "Select"}
        </Button>
      </div>
    </Card>
  );
}
