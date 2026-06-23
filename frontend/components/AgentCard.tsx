import Link from "next/link";
import type { AgentCardDTO } from "@/lib/types";
import { RatingStars } from "./RatingStars";

/** Marketplace unit card → links to the agent storefront. Builder-private fields never appear. */
export function AgentCard({ agent }: { agent: AgentCardDTO }) {
  return (
    <Link href={`/client/agents/${agent.id}`} className="block h-full rounded-xl focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent focus-visible:ring-offset-2 focus-visible:ring-offset-canvas">
      <article className="panel panel-hover hud flex h-full flex-col overflow-hidden">
        {/* cover strip */}
        <div className="relative h-20 border-b border-line bg-surface-2">
          {agent.coverUrl && (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={agent.coverUrl} alt="" className="size-full object-cover" />
          )}
          {agent.featured && (
            <span className="absolute right-2 top-2 rounded border border-accent/50 bg-accent/15 px-1.5 py-0.5 font-mono text-[0.6rem] font-bold uppercase tracking-wider text-accent">
              🔥 Hot
            </span>
          )}
          <span className="absolute -bottom-4 left-4 grid size-9 place-items-center overflow-hidden rounded-md border border-line-bright bg-canvas">
            {agent.logoUrl ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img src={agent.logoUrl} alt="" className="size-full object-cover" />
            ) : (
              <span className="size-3 rounded-[2px] bg-accent" aria-hidden />
            )}
          </span>
        </div>

        <div className="flex flex-1 flex-col gap-2 p-4 pt-6">
          <div>
            <h3 className="truncate text-base font-bold tracking-tight">{agent.name}</h3>
            <p className="font-mono text-[0.65rem] text-dim">by {agent.builderName}</p>
          </div>
          {agent.tagline && <p className="line-clamp-2 text-sm text-muted">{agent.tagline}</p>}
          <div className="flex flex-wrap gap-1.5">
            {agent.categories.map((c) => (
              <span
                key={c}
                className="rounded border border-line bg-surface-2 px-1.5 py-0.5 font-mono text-[0.6rem] uppercase tracking-wider text-cyan"
              >
                {c}
              </span>
            ))}
          </div>
          <div className="mt-auto flex items-end justify-between border-t border-line pt-3">
            <div className="space-y-1">
              <RatingStars avg={agent.ratingAvg} count={agent.ratingCount} />
              <p className="font-mono text-[0.6rem] uppercase tracking-wider text-dim">
                {agent.requestCount} requests · rep {agent.reputationScore}
              </p>
            </div>
            <p className="tabular font-mono text-sm font-bold text-accent">{agent.price} cr</p>
          </div>
        </div>
      </article>
    </Link>
  );
}
