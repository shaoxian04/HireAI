"use client";

import type { AgentOptionDTO } from "@/lib/types";
import { Button, Modal } from "@/components/ui";

interface Props {
  open: boolean;
  shortlist: AgentOptionDTO[];
  nearMisses: AgentOptionDTO[];
  budget: number;
  onSelect: (option: AgentOptionDTO) => void;
  onClose: () => void;
}

export function ShortlistPanel({ open, shortlist, nearMisses, budget, onSelect, onClose }: Props) {
  const empty = shortlist.length === 0 && nearMisses.length === 0;
  return (
    <Modal open={open} onClose={onClose} ariaLabel="Pick your agent">
      <div className="flex items-center justify-between gap-4 border-b border-line px-6 py-4">
        <div>
          <h2 className="text-lg font-extrabold tracking-tight">Pick your agent</h2>
          <p className="mt-0.5 font-mono text-xs text-muted">
            {shortlist.length} in budget · ranked for your task · budget {budget} cr
          </p>
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close"
          className="grid size-8 place-items-center rounded-md border border-line font-mono text-dim transition hover:border-line-bright hover:text-fg"
        >
          ✕
        </button>
      </div>

      {empty ? (
        <p className="px-6 py-8 font-mono text-sm text-dim">
          No agents match this category yet. Adjust the category or budget and search again.
        </p>
      ) : (
        <>
          {shortlist.length > 0 && (
            <div className="grid grid-cols-1 gap-3 px-6 py-5 sm:grid-cols-2">
              {shortlist.map((o, i) => (
                <OptionCard key={o.agentVersionId} option={o} rank={i + 1} onSelect={onSelect} />
              ))}
            </div>
          )}
          {nearMisses.length > 0 && (
            <details className="border-t border-line px-6 py-4">
              <summary className="cursor-pointer font-mono text-xs uppercase tracking-wider text-muted">
                Above your budget · {nearMisses.length}
              </summary>
              <p className="mt-2 font-mono text-xs text-dim">
                These cost more than your {budget} cr budget — selecting one pays its price.
              </p>
              <div className="mt-3 space-y-3">
                {nearMisses.map((o) => (
                  <NearMissRow key={o.agentVersionId} option={o} onSelect={onSelect} />
                ))}
              </div>
            </details>
          )}
        </>
      )}
    </Modal>
  );
}

function Avatar({ name, logoUrl, size = 44 }: { name: string; logoUrl: string | null; size?: number }) {
  const dim = { width: size, height: size };
  if (logoUrl) {
    return (
      // eslint-disable-next-line @next/next/no-img-element
      <img
        src={logoUrl}
        alt=""
        style={dim}
        className="shrink-0 rounded-full border border-line-bright object-cover"
      />
    );
  }
  return (
    <span
      aria-hidden
      style={dim}
      className="grid shrink-0 place-items-center rounded-full border border-line-bright bg-surface font-mono text-base font-bold text-muted"
    >
      {name.trim().charAt(0).toUpperCase() || "?"}
    </span>
  );
}

function Stars({ score }: { score: number }) {
  const filled = Math.max(0, Math.min(5, Math.round(score / 20)));
  return (
    <span aria-label={`${score} reputation`} className="text-sm">
      <span className="text-accent">{"★".repeat(filled)}</span>
      <span className="text-line-bright">{"★".repeat(5 - filled)}</span>
    </span>
  );
}

function OptionCard({
  option,
  rank,
  onSelect,
}: {
  option: AgentOptionDTO;
  rank: number;
  onSelect: (o: AgentOptionDTO) => void;
}) {
  const best = rank === 1;
  return (
    <div
      className={`flex flex-col gap-3 rounded-lg border p-4 transition ${
        best ? "border-accent/55 glow" : "border-line bg-surface-2 hover:border-line-bright"
      }`}
    >
      <div className="flex items-start justify-between gap-2">
        <span
          className={`rounded px-1.5 py-0.5 font-mono text-[0.6rem] font-bold tracking-wider ${
            best ? "bg-accent text-ink" : "border border-line text-dim"
          }`}
        >
          {best ? "★ BEST MATCH" : `#${rank}`}
        </span>
        <span className="font-mono text-lg font-bold text-accent tabular">
          {option.price}
          <span className="text-xs font-medium text-muted"> cr</span>
        </span>
      </div>
      <div className="flex items-center gap-3">
        <Avatar name={option.agentName} logoUrl={option.logoUrl} />
        <div className="min-w-0">
          <p className="truncate font-semibold">{option.agentName}</p>
          {option.tagline && <p className="truncate text-xs text-muted">{option.tagline}</p>}
        </div>
      </div>
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 font-mono text-xs text-muted">
        <Stars score={option.reputationScore} />
        <span className="tabular">{option.reputationScore} rep</span>
        {option.outputFormat && (
          <span className="rounded border border-line px-1.5 py-0.5">{option.outputFormat}</span>
        )}
      </div>
      <div className="mt-auto flex items-center justify-between pt-1">
        <span className="inline-flex items-center gap-1.5 font-mono text-xs">
          <span
            className={`size-1.5 rounded-full ${
              option.availability === "AVAILABLE" ? "bg-accent" : "bg-amber"
            }`}
          />
          {option.availability === "AVAILABLE" ? "available" : "busy"}
        </span>
        <Button
          variant={best ? "primary" : "secondary"}
          className="!px-3 !py-1.5"
          onClick={() => onSelect(option)}
        >
          Select<span aria-hidden> ▸</span>
        </Button>
      </div>
    </div>
  );
}

function NearMissRow({
  option,
  onSelect,
}: {
  option: AgentOptionDTO;
  onSelect: (o: AgentOptionDTO) => void;
}) {
  return (
    <div className="flex items-center justify-between gap-3">
      <div className="flex min-w-0 items-center gap-2.5">
        <Avatar name={option.agentName} logoUrl={option.logoUrl} size={36} />
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold">{option.agentName}</p>
          <p className="truncate font-mono text-xs text-dim">★ {option.reputationScore} rep</p>
        </div>
      </div>
      <Button
        variant="secondary"
        className="!whitespace-nowrap !px-3 !py-1.5"
        onClick={() => onSelect(option)}
      >
        Select · pays {option.price} cr
      </Button>
    </div>
  );
}
