"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import { RatingStars } from "@/components/RatingStars";
import { ReviewList } from "@/components/ReviewList";
import { Button } from "@/components/ui";
import type { AgentProfileDTO } from "@/lib/types";

function Storefront() {
  const { id } = useParams<{ id: string }>();
  const [profile, setProfile] = useState<AgentProfileDTO | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;
    // Reset so navigating between storefronts never flashes the previous agent.
    setProfile(null);
    setError(null);
    api<AgentProfileDTO>(`/catalogue/agents/${id}`)
      .then(setProfile)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Failed to load agent"));
  }, [id]);

  if (error)
    return (
      <p role="alert" className="rounded-md border border-red/30 bg-red/10 px-3 py-2 font-mono text-xs text-red">
        {error}
      </p>
    );
  if (!profile) return <p className="font-mono text-sm text-dim">Loading storefront…</p>;

  const { card, stats } = profile;

  return (
    <div className="space-y-6">
      <Link href="/client" className="font-mono text-xs text-dim transition hover:text-accent">
        ← marketplace
      </Link>

      {/* hero */}
      <div className="relative h-40 overflow-hidden rounded-xl border border-line bg-surface-2">
        {card.coverUrl && (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={card.coverUrl} alt="" className="size-full object-cover" />
        )}
        <span className="absolute bottom-0 left-6 grid size-16 translate-y-1/3 place-items-center overflow-hidden rounded-lg border-2 border-accent/50 bg-canvas">
          {card.logoUrl ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={card.logoUrl} alt="" className="size-full object-cover" />
          ) : (
            <span className="size-5 rounded-[3px] bg-accent" />
          )}
        </span>
      </div>

      <div className="grid gap-6 lg:grid-cols-[1fr_260px]">
        <div className="space-y-6 pt-4">
          <header className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <h1 className="text-2xl font-extrabold tracking-tight">{card.name}</h1>
              <p className="mt-1 font-mono text-xs text-dim">
                by {card.builderName} · rep <span className="tabular">{card.reputationScore}</span>
              </p>
            </div>
            <RatingStars avg={card.ratingAvg} count={card.ratingCount} />
          </header>

          {card.tagline && <p className="text-lg text-fg">{card.tagline}</p>}

          {profile.description && (
            <section>
              <p className="eyebrow mb-2">What this agent does</p>
              <p className="whitespace-pre-wrap text-sm leading-relaxed text-muted">{profile.description}</p>
            </section>
          )}

          {profile.sampleOutput && (
            <section>
              <p className="eyebrow mb-2">Sample output</p>
              <pre className="overflow-auto rounded-md border border-line bg-canvas p-4 font-mono text-xs leading-relaxed text-fg">
                {profile.sampleOutput}
              </pre>
            </section>
          )}

          {profile.galleryUrls.length > 0 && (
            <section>
              <p className="eyebrow mb-2">Gallery</p>
              <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                {profile.galleryUrls.map((u) => (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img key={u} src={u} alt="" className="h-24 w-full rounded-md border border-line object-cover" />
                ))}
              </div>
            </section>
          )}

          <section>
            <p className="eyebrow mb-2">Output contract</p>
            <div className="rounded-md border border-line bg-surface-2 p-4 font-mono text-xs text-muted">
              <p>
                format ▸ <span className="text-cyan">{profile.outputSpec.format}</span>
              </p>
              {profile.outputSpec.schema && <p className="mt-1">schema ▸ {profile.outputSpec.schema}</p>}
              {profile.outputSpec.acceptanceCriteria && (
                <p className="mt-1">accepts ▸ {profile.outputSpec.acceptanceCriteria}</p>
              )}
            </div>
          </section>

          <section>
            <p className="eyebrow mb-2">Track record</p>
            <div className="grid grid-cols-3 gap-px overflow-hidden rounded-xl border border-line bg-line">
              {[
                { v: stats.requestCount, l: "requests" },
                { v: stats.successRate == null ? "—" : `${Math.round(stats.successRate * 100)}%`, l: "success" },
                {
                  v: stats.avgTurnaroundSeconds == null ? "—" : `${Math.round(stats.avgTurnaroundSeconds)}s`,
                  l: "avg turnaround",
                },
              ].map((s) => (
                <div key={s.l} className="bg-surface px-4 py-4">
                  <p className="tabular text-xl font-extrabold text-fg">{s.v}</p>
                  <p className="mt-1 font-mono text-[0.6rem] uppercase tracking-[0.18em] text-dim">{s.l}</p>
                </div>
              ))}
            </div>
          </section>

          <section>
            <p className="eyebrow mb-2">Reviews</p>
            <ReviewList reviews={profile.reviews} />
            <p className="mt-2 font-mono text-[0.6rem] uppercase tracking-wider text-dim">
              Demo ratings — review submission opens with validation &amp; settlement (Module 4/5).
            </p>
          </section>
        </div>

        {/* sticky booking sidebar */}
        <aside className="lg:sticky lg:top-24 lg:self-start">
          <div className="panel hud space-y-4 p-5">
            <div>
              <p className="eyebrow">Price</p>
              <p className="tabular mt-1 text-3xl font-extrabold text-accent">
                {card.price} <span className="text-sm font-semibold text-muted">cr</span>
              </p>
            </div>
            <p className="font-mono text-xs text-muted">
              ≤ <span className="tabular">{card.maxExecutionSeconds}s</span> execution · escrow-protected
            </p>
            <div className="flex flex-wrap gap-1.5">
              {card.categories.map((c) => (
                <span key={c} className="rounded border border-line bg-surface-2 px-1.5 py-0.5 font-mono text-[0.6rem] uppercase tracking-wider text-cyan">
                  {c}
                </span>
              ))}
            </div>
            <Link href={`/client/agents/${card.id}/book`} aria-label="Book this agent">
              <Button className="w-full">Book this agent ▸</Button>
            </Link>
            <p className="font-mono text-[0.6rem] leading-relaxed text-dim">
              You accept this agent&apos;s output contract; credits freeze in escrow on submit.
            </p>
          </div>
        </aside>
      </div>
    </div>
  );
}

export default function Page() {
  return (
    <AppShell>
      <RoleGuard role="CLIENT">
        <Storefront />
      </RoleGuard>
    </AppShell>
  );
}
