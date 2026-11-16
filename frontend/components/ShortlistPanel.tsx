"use client";

import type { AgentOptionDTO, NoMatchDTO } from "@/lib/types";
import { Button, Modal } from "@/components/ui";
import { DeliveryRecord } from "@/components/DeliveryRecord";
import { ContractDeclines } from "@/components/ContractDeclines";
import { VersionFallbackNotice } from "@/components/VersionFallbackNotice";
import type { VersionFallbackDTO } from "@/lib/types";

interface Props {
  open: boolean;
  shortlist: AgentOptionDTO[];
  nearMisses: AgentOptionDTO[];
  budget: number;
  /** Every in-budget agent's profile was below the relevance floor for this task text. */
  noCloseMatch?: boolean;
  /** Why the shortlist is empty: the blocking terms (or the relevance floor) with agent counts. */
  noMatch?: NoMatchDTO | null;
  /** The plain label of a Contract parameter (falls back to its name). */
  labelFor?: (parameter: string) => string;
  /** The Client chose to leave `parameter` unset and search again. Never called on its own. */
  onRelax?: (parameter: string) => void;
  /** The terms were matched under the previous Template version (no agent accepts the current one yet). */
  fallback?: VersionFallbackDTO | null;
  /** The plain title of the terms' Template, for the fallback notice. */
  templateTitle?: string;
  /** True when the terms (some or all) were taken from the description, not set by the Client (#101). */
  termsExtracted?: boolean;
  onSelect: (option: AgentOptionDTO) => void;
  onClose: () => void;
}

export function ShortlistPanel({
  open,
  shortlist,
  nearMisses,
  budget,
  noCloseMatch = false,
  noMatch = null,
  labelFor = (p) => p,
  onRelax,
  fallback = null,
  templateTitle,
  termsExtracted = false,
  onSelect,
  onClose,
}: Props) {
  // Terms (not just the task text) excluded agents: explain which, and how relaxing them would help.
  const termsBlocked =
    shortlist.length === 0 && !!noMatch?.exclusions.some((e) => e.blocker !== "RELEVANCE_FLOOR");
  const empty = shortlist.length === 0 && nearMisses.length === 0 && !noCloseMatch && !termsBlocked;
  return (
    <Modal open={open} onClose={onClose} ariaLabel="Pick your agent">
      <div className="flex items-center justify-between gap-4 border-b border-line px-6 py-4">
        <div>
          <h2 className="text-lg font-bold tracking-tight">Pick your agent</h2>
          <p className="mt-0.5 text-xs text-muted">
            {shortlist.length} in budget · ranked for your task · budget {budget} cr
          </p>
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close"
          className="grid size-8 place-items-center rounded-md border border-line text-dim transition hover:border-line-bright hover:text-fg"
        >
          ✕
        </button>
      </div>

      {fallback && (
        <div className="border-b border-line px-6 py-4">
          <VersionFallbackNotice fallback={fallback} templateTitle={templateTitle} context="preview" />
        </div>
      )}

      {empty ? (
        <p className="px-6 py-8 text-sm text-dim">
          No agents match this category yet. Adjust the category or budget and search again.
        </p>
      ) : (
        <>
          {termsBlocked && noMatch && (
            <NoMatchExplanation
              noMatch={noMatch}
              labelFor={labelFor}
              onRelax={onRelax}
              hasNearMisses={nearMisses.length > 0}
              extracted={termsExtracted}
            />
          )}
          {!termsBlocked && noCloseMatch && shortlist.length === 0 && (
            <div role="status" className="px-6 py-6">
              <p className="text-sm font-semibold">No agent matches this task closely</p>
              <p className="mt-1 text-sm text-dim">
                Agents in this category don&apos;t describe work like yours. Say more about what you
                need in the description, or try another category.
                {nearMisses.length > 0 && " Closer matches above your budget are listed below."}
              </p>
            </div>
          )}
          {shortlist.length > 0 && (
            <div className="grid grid-cols-1 gap-3 px-6 py-5 sm:grid-cols-2">
              {shortlist.map((o, i) => (
                <OptionCard key={o.agentVersionId} option={o} rank={i + 1} onSelect={onSelect} />
              ))}
            </div>
          )}
          {nearMisses.length > 0 && (
            <details className="border-t border-line px-6 py-4">
              <summary className="cursor-pointer text-xs text-muted">
                Above your budget · {nearMisses.length}
              </summary>
              <p className="mt-2 text-xs text-dim">
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
      className="grid shrink-0 place-items-center rounded-full border border-line-bright bg-surface text-base font-bold text-muted"
    >
      {name.trim().charAt(0).toUpperCase() || "?"}
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
      className={`flex flex-col gap-3 rounded-lg border p-4 transition ${best ? "border-accent/55" : "border-line bg-surface-2 hover:border-line-bright"}`}
    >
      <div className="flex items-start justify-between gap-2">
        <span
          className={`rounded px-1.5 py-0.5 text-xs font-bold ${best ? "bg-accent text-ink" : "border border-line text-dim"}`}
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
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted">
        <DeliveryRecord
          reliabilitySum={option.reliabilitySum}
          reliabilityCount={option.reliabilityCount}
        />
        {option.outputFormat && (
          <span className="rounded border border-line px-1.5 py-0.5">{option.outputFormat}</span>
        )}
        <ContractDeclines rate={option.contractDeclines} className="basis-full" />
      </div>
      <div className="mt-auto flex items-center justify-between pt-1">
        <span className="inline-flex items-center gap-1.5 text-xs">
          <span
            className={`size-1.5 rounded-full ${option.availability === "AVAILABLE" ? "bg-accent" : "bg-amber"}`}
          />
          {option.availability === "AVAILABLE" ? "available" : "busy"}
        </span>
        <Button
          variant={best ? "primary" : "secondary"}
          className="!px-3 !py-1.5"
          onClick={() => onSelect(option)}
        >
          Select<span aria-hidden></span>
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
          <p className="truncate text-xs text-dim">
            <DeliveryRecord
              reliabilitySum={option.reliabilitySum}
              reliabilityCount={option.reliabilityCount}
            />
          </p>
          {option.contractDeclines && (
            <p className="truncate text-xs text-dim">
              <ContractDeclines rate={option.contractDeclines} />
            </p>
          )}
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

function agents(n: number) {
  return `${n} ${n === 1 ? "agent" : "agents"}`;
}

/**
 * The no-match explanation (spec D35): what ruled the in-budget agents out, and the relax options the
 * Client may take. The platform never relaxes the terms itself; a suggestion is a button the Client presses.
 */
function NoMatchExplanation({
  noMatch,
  labelFor,
  onRelax,
  hasNearMisses,
  extracted,
}: {
  noMatch: NoMatchDTO;
  labelFor: (parameter: string) => string;
  onRelax?: (parameter: string) => void;
  hasNearMisses: boolean;
  extracted: boolean;
}) {
  return (
    <div role="status" className="space-y-4 px-6 py-6">
      <div>
        <p className="text-sm font-semibold">No agent accepts these terms</p>
        <p className="mt-1 text-sm text-dim">
          {extracted
            ? "Agents in this category and budget were ruled out by the terms taken from your description."
            : "Agents in this category and budget were ruled out by your output terms."}
          {hasNearMisses && " Agents above your budget that accept them are listed below."}
        </p>
      </div>
      <ul className="divide-y divide-line rounded-lg border border-line bg-surface-2">
        {noMatch.exclusions.map((e, i) => (
          <li key={i} className="flex items-baseline justify-between gap-4 px-4 py-2.5 text-sm">
            <span className="min-w-0 text-fg">
              {e.blocker === "PARAMETER" && e.parameter
                ? <>Their offers don&apos;t accept your <span className="font-medium">{labelFor(e.parameter)}</span></>
                : e.blocker === "TEMPLATE"
                  ? "They don’t deliver this kind of output"
                  : "Their profiles don’t describe work like yours"}
            </span>
            <span className="shrink-0 font-mono text-xs text-muted tabular">{agents(e.agents)}</span>
          </li>
        ))}
      </ul>
      {noMatch.suggestions.length > 0 && onRelax && (
        <div className="space-y-2">
          <p className="eyebrow">Relax a term</p>
          <div className="flex flex-wrap gap-2">
            {noMatch.suggestions.map((sg) => (
              <Button
                key={sg.parameter}
                variant="secondary"
                className="!px-3 !py-1.5"
                onClick={() => onRelax(sg.parameter)}
              >
                Drop {labelFor(sg.parameter)} → {agents(sg.agents)}
              </Button>
            ))}
          </div>
          <p className="text-xs text-dim">We never change your terms for you. Dropping one searches again without it.</p>
        </div>
      )}
    </div>
  );
}
