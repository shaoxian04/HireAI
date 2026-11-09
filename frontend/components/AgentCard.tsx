import Link from "next/link";
import type { AgentCardDTO } from "@/lib/types";
import { RatingStars } from "./RatingStars";
import { DeliveryRecord } from "@/components/DeliveryRecord";

/** Deep, ink-like hues for monograms. Picked by a stable hash of the name so an agent keeps its colour. */
const MONOGRAM_HUES = ["#2541e0", "#0a6f8a", "#6a43d1", "#a2431b", "#0b7a50", "#3b4763", "#9b2d6f"];

function hueFor(name: string): string {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0;
  return MONOGRAM_HUES[h % MONOGRAM_HUES.length]!;
}

/** Initials tile for an agent with no logo — distinct per agent, unlike a shared placeholder. */
export function Monogram({ name, className = "text-xl" }: { name: string; className?: string }) {
  const initials =
    name
      .replace(/[^A-Za-z0-9 ]/g, " ")
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((w) => w[0]!.toUpperCase())
      .join("") || "?";
  return (
    <span
      aria-hidden
      className={`grid size-full place-items-center font-display font-extrabold text-white ${className}`}
      style={{ background: hueFor(name) }}
    >
      {initials}
    </span>
  );
}

/** Marketplace unit card → links to the agent storefront. Builder-private fields never appear. */
export function AgentCard({ agent }: { agent: AgentCardDTO }) {
  return (
    <Link
      href={`/client/agents/${agent.id}`}
      className="group block h-full rounded-xl focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent focus-visible:ring-offset-2 focus-visible:ring-offset-canvas"
    >
      <article className="docket flex h-full flex-col overflow-hidden transition duration-150 group-hover:-translate-y-0.5 group-hover:border-accent/50 group-hover:shadow-[0_12px_28px_-14px_rgb(20_42_156/0.35)]">
        {agent.coverUrl && (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={agent.coverUrl} alt="" className="h-28 w-full border-b border-line object-cover" />
        )}

        <div className="flex flex-1 flex-col gap-3 p-5">
          <div className="flex items-start gap-3.5">
            <span className="size-12 shrink-0 overflow-hidden rounded-lg">
              {agent.logoUrl ? (
                // eslint-disable-next-line @next/next/no-img-element
                <img src={agent.logoUrl} alt="" className="size-full object-cover" />
              ) : (
                <Monogram name={agent.name} />
              )}
            </span>
            <div className="min-w-0 flex-1 pt-0.5">
              <div className="flex items-center gap-2">
                <h3 className="truncate text-[1.0625rem] font-bold leading-tight group-hover:text-accent">{agent.name}</h3>
                {agent.featured && (
                  <span className="shrink-0 rounded-full bg-amber px-1.5 py-px text-[0.6875rem] font-bold text-ink">
                    Hot
                  </span>
                )}
              </div>
              <p className="mt-0.5 text-[0.8125rem] text-dim">by {agent.builderName}</p>
            </div>
          </div>

          <p className="line-clamp-2 min-h-[2.5rem] text-sm leading-snug text-muted">
            {/* With no tagline, say what the card's own data does tell you, not "no description". */}
            {agent.tagline ||
              `Takes ${agent.categories.length ? agent.categories.join(" and ") : "general"} work and delivers within ${agent.maxExecutionSeconds} seconds.`}
          </p>

          <div className="mt-auto flex items-end justify-between gap-3">
            <div className="flex min-w-0 flex-wrap gap-1.5">
              {agent.categories.map((c) => (
                <span key={c} className="truncate rounded-full bg-surface-2 px-2 py-0.5 text-xs font-medium text-muted">
                  {c}
                </span>
              ))}
            </div>
            <p className="display tabular shrink-0 text-[1.9rem] text-fg">{agent.price} cr</p>
          </div>
        </div>

        <div className="docket-tear" />
        <div className="flex items-center justify-between gap-3 px-5 py-3 text-xs text-dim">
          {agent.ratingCount === 0 && agent.reliabilityCount === 0 ? (
            <>
              <span className="inline-flex items-center gap-1 rounded-md bg-amber/15 px-2 py-0.5 font-semibold text-amber">
                <svg viewBox="0 0 12 12" className="size-3" aria-hidden>
                  <path d="M6 .8l1.3 3.1 3.3.3-2.5 2.2.8 3.3L6 8 3.1 9.7l.8-3.3L1.4 4.2l3.3-.3z" fill="currentColor" />
                </svg>
                New agent
              </span>
              <span className="tabular shrink-0">
                {agent.requestCount} {agent.requestCount === 1 ? "request" : "requests"} so far
              </span>
            </>
          ) : (
            <>
              <RatingStars avg={agent.ratingAvg} count={agent.ratingCount} />
              <span className="flex items-center gap-3">
                {agent.reliabilityCount > 0 && (
                  <DeliveryRecord reliabilitySum={agent.reliabilitySum} reliabilityCount={agent.reliabilityCount} />
                )}
                <span className="tabular shrink-0">
                  {agent.requestCount} {agent.requestCount === 1 ? "request" : "requests"}
                </span>
              </span>
            </>
          )}
        </div>
      </article>
    </Link>
  );
}
