"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import { Monogram } from "@/components/AgentCard";
import { ContractTerms } from "@/components/ContractTerms";
import { BottomBar } from "@/components/BottomBar";
import { formatDuration } from "@/lib/format";
import { DeliveryRecord } from "@/components/DeliveryRecord";
import { RatingStars } from "@/components/RatingStars";
import { ReviewList } from "@/components/ReviewList";
import { Skeleton, ButtonLink } from "@/components/ui";
import type { AgentProfileDTO } from "@/lib/types";

function Storefront() {
  const { id } = useParams<{ id: string }>();
  const [profile, setProfile] = useState<AgentProfileDTO | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;
    // Reset so navigating between storefronts never flashes the previous agent.
    // eslint-disable-next-line react-hooks/set-state-in-effect -- intentional reset-on-navigation so a new :id never flashes stale data
    setProfile(null);
    setError(null);
    api<AgentProfileDTO>(`/catalogue/agents/${id}`)
      .then(setProfile)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Failed to load agent"));
  }, [id]);

  if (error)
    return (
      <p role="alert" className="rounded-md border border-red/30 bg-red/10 px-3 py-2 text-sm text-red">
        {error}
      </p>
    );
  if (!profile)
    return (
      <div role="status" className="space-y-4">
        <span className="sr-only">Loading storefront…</span>
        <Skeleton className="h-3 w-24" />
        <div className="flex gap-4">
          <Skeleton className="size-14" />
          <div className="flex-1 space-y-2 pt-1">
            <Skeleton className="h-6 w-1/3" />
            <Skeleton className="h-3 w-1/5" />
          </div>
        </div>
        <Skeleton className="h-32 w-full" />
      </div>
    );

  const { card, stats } = profile;
  const spec = profile.outputSpec;

  return (
    <div className="space-y-8">
      <Link href="/client" className="inline-block text-sm font-medium text-muted transition hover:text-accent">
        ← Marketplace
      </Link>

      {card.coverUrl && (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={card.coverUrl} alt="" className="h-44 w-full rounded-lg border border-line object-cover" />
      )}

      <div className="grid gap-10 lg:grid-cols-[1fr_280px]">
        <div className="min-w-0 space-y-10">
          <header className="flex items-start gap-4">
            <span className="size-20 shrink-0 overflow-hidden rounded-xl">
              {card.logoUrl ? (
                // eslint-disable-next-line @next/next/no-img-element
                <img src={card.logoUrl} alt="" className="size-full object-cover" />
              ) : (
                <Monogram name={card.name} className="text-3xl" />
              )}
            </span>
            <div className="min-w-0 space-y-1.5">
              <h1 className="display text-[2.9rem]">{card.name}</h1>
              <p className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-dim">
                <span>by {card.builderName}</span>
                <RatingStars avg={card.ratingAvg} count={card.ratingCount} />
              </p>
              {card.tagline && <p className="pt-1 text-[1.0625rem] text-fg">{card.tagline}</p>}
            </div>
          </header>

          {profile.description && (
            <section className="space-y-2">
              <h2 className="eyebrow">What this agent does</h2>
              <p className="whitespace-pre-wrap text-[0.9375rem] leading-relaxed text-muted">{profile.description}</p>
            </section>
          )}

          <section className="docket overflow-hidden">
            <div className="px-5 py-4">
              <h2 className="eyebrow">Output contract</h2>
              <p className="mt-1 text-sm text-muted">
                Booking this agent binds your task to this contract. Validation and any dispute are judged against it.
              </p>
            </div>
            <div className="docket-tear" />
            <ContractTerms spec={spec} />
          </section>

          {profile.sampleOutput && (
            <section className="space-y-2">
              <h2 className="eyebrow">Sample output</h2>
              <pre className="overflow-auto whitespace-pre-wrap break-words rounded-lg border border-line bg-surface p-4 font-mono text-xs leading-relaxed text-fg">
                {profile.sampleOutput}
              </pre>
            </section>
          )}

          {profile.galleryUrls.length > 0 && (
            <section className="space-y-2">
              <h2 className="eyebrow">Gallery</h2>
              <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                {profile.galleryUrls.map((u) => (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img key={u} src={u} alt="" className="h-24 w-full rounded-md border border-line object-cover" />
                ))}
              </div>
            </section>
          )}

          <section className="space-y-3">
            <h2 className="eyebrow">Track record</h2>
            <dl className="grid grid-cols-3 divide-x divide-line rounded-xl border border-line bg-surface">
              <div className="px-5 py-4">
                <dt className="text-xs text-dim">Requests</dt>
                <dd className="display tabular mt-1 text-[2.25rem] text-fg">{stats.requestCount}</dd>
              </div>
              <div className="px-5 py-4">
                <dt className="text-xs text-dim">Delivered</dt>
                <dd className="display tabular mt-1 text-[2.25rem] text-fg">
                  {stats.completedCount}
                  {stats.successRate != null && (
                    <span className="ml-2 font-sans text-sm font-semibold text-dim">{Math.round(stats.successRate * 100)}%</span>
                  )}
                </dd>
              </div>
              <div className="px-5 py-4">
                <dt className="text-xs text-dim">Avg turnaround</dt>
                <dd className="display tabular mt-1 text-[2.25rem] text-fg">
                  {formatDuration(stats.avgTurnaroundSeconds)}
                </dd>
              </div>
            </dl>
            <p className="text-sm text-muted">
              Settled outcomes:{" "}
              <DeliveryRecord reliabilitySum={card.reliabilitySum} reliabilityCount={card.reliabilityCount} />
            </p>
          </section>

          <section className="space-y-3">
            <h2 className="eyebrow">Reviews</h2>
            <ReviewList reviews={profile.reviews} />
          </section>
        </div>

        {/* sticky booking sidebar */}
        <aside className="lg:sticky lg:top-20 lg:self-start">
          <div className="docket overflow-hidden">
            <div className="bg-brand px-5 pb-5 pt-4 text-white">
              <p className="text-sm font-medium text-white/80">Price per task</p>
              <p className="display tabular mt-1 text-[3.5rem]">
                {card.price} <span className="text-2xl text-white/75">credits</span>
              </p>
            </div>
            <div className="space-y-4 p-5">
            <dl className="space-y-2 text-sm">
              <div className="flex justify-between gap-3">
                <dt className="text-dim">Time limit</dt>
                <dd className="tabular font-semibold text-fg">{card.maxExecutionSeconds}s</dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-dim">Category</dt>
                <dd className="truncate text-right font-semibold text-fg">{card.categories.join(", ")}</dd>
              </div>
            </dl>
            <ButtonLink href={`/client/agents/${card.id}/book`} className="w-full">Book this agent</ButtonLink>
            <p className="text-xs leading-relaxed text-dim">
              The price is frozen in escrow when you submit, and released only when you accept the result.
            </p>
            </div>
          </div>
        </aside>
      </div>

      {/* Phones: the booking panel sits below the whole profile, so keep price + Book in reach. */}
      <div className="h-20 lg:hidden" aria-hidden />
      <BottomBar>
        <div>
          <p className="text-xs text-dim">Price per task</p>
          <p className="display tabular text-[1.75rem] !leading-none text-fg">{card.price} cr</p>
        </div>
        <ButtonLink href={`/client/agents/${card.id}/book`}>Book this agent</ButtonLink>
      </BottomBar>
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
