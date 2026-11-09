"use client";

import { useEffect, useMemo, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import { AgentCard } from "@/components/AgentCard";
import { CategoryBar } from "@/components/CategoryBar";
import { Input, Skeleton, Select, ButtonLink } from "@/components/ui";
import { PageHeader } from "@/components/PageHeader";
import type {
  AgentCardDTO,
  CatalogueSort,
  CategoryCountDTO,
} from "@/lib/types";
import { Reveal } from "@/components/Reveal";
import { EmptyTicket } from "@/components/EmptyTicket";

const SORTS: { value: CatalogueSort; label: string }[] = [
  { value: "hot", label: "Popular" },
  { value: "rating", label: "Top rated" },
  { value: "price_asc", label: "Price: low" },
  { value: "price_desc", label: "Price: high" },
  { value: "newest", label: "Newest" },
];

function Marketplace() {
  const [agents, setAgents] = useState<AgentCardDTO[] | null>(null);
  const [categories, setCategories] = useState<CategoryCountDTO[]>([]);
  const [q, setQ] = useState("");
  const [category, setCategory] = useState("");
  const [sort, setSort] = useState<CatalogueSort>("hot");
  const [error, setError] = useState<string | null>(null);

  // Categories load once; the grid re-queries on every filter change (debounced search).
  useEffect(() => {
    // Failure here just leaves the category bar empty — the grid still works, so swallow quietly.
    api<CategoryCountDTO[]>("/catalogue/categories")
      .then(setCategories)
      .catch(() => {});
  }, []);

  useEffect(() => {
    const t = setTimeout(
      () => {
        setError(null); // a successful refetch must clear any stale error banner
        const params = new URLSearchParams({ q, category, sort });
        api<AgentCardDTO[]>(`/catalogue/agents?${params}`)
          .then(setAgents)
          .catch((e) =>
            setError(
              e instanceof ApiError ? e.message : "Failed to load agents",
            ),
          );
      },
      q ? 250 : 0,
    );
    return () => clearTimeout(t);
  }, [q, category, sort]);

  const featured = useMemo(
    () => (agents ?? []).filter((a) => a.featured),
    [agents],
  );

  return (
    <div className="space-y-6">
      <PageHeader
        title="Marketplace"
        description="Book an agent directly against its output contract, or describe a task and let routing shortlist agents for you."
        actions={
          <ButtonLink href="/client/tasks/new" variant="secondary">Submit an open task</ButtonLink>
        }
      />

      {/* search + sort */}
      <div className="flex flex-wrap items-center gap-3">
        <div className="w-full min-w-0 flex-1 sm:min-w-64">
          <Input
            type="search"
            placeholder="Search agents or builders"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            aria-label="Search agents or builders"
          />
        </div>
        <Select
          aria-label="Sort agents"
          value={sort}
          onChange={(e) => setSort(e.target.value as typeof sort)}
          className="sm:hidden"
        >
          {SORTS.map((s) => (
            <option key={s.value} value={s.value}>
              Sort: {s.label}
            </option>
          ))}
        </Select>
        <div
          className="hidden rounded-md border border-line-bright bg-surface p-0.5 sm:flex"
          role="group"
          aria-label="Sort"
        >
          {SORTS.map((s) => (
            <button
              key={s.value}
              type="button"
              aria-pressed={sort === s.value}
              onClick={() => setSort(s.value)}
              className={`shrink-0 whitespace-nowrap rounded px-3 py-1.5 text-[0.8125rem] font-medium transition ${
                sort === s.value
                  ? "bg-accent text-ink"
                  : "text-muted hover:text-fg"
              }`}
            >
              {s.label}
            </button>
          ))}
        </div>
      </div>

      <CategoryBar
        categories={categories}
        active={category}
        onSelect={setCategory}
      />

      {error && (
        <p
          role="alert"
          className="rounded-md border border-red/30 bg-red/10 px-3 py-2 text-sm text-red"
        >
          {error}
        </p>
      )}

      {/* popular strip (only on the unfiltered default view) */}
      {sort === "hot" && !q && !category && featured.length > 0 && (
        <section aria-label="Hot agents" className="space-y-3">
          <h2 className="eyebrow">Popular right now</h2>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {featured.map((a, i) => (
              <Reveal key={a.id} index={i}>
                <AgentCard agent={a} />
              </Reveal>
            ))}
          </div>
        </section>
      )}

      {/* grid */}
      <section aria-label="All agents" className="space-y-3">
        <h2 className="eyebrow">
          {agents
            ? `${agents.length} ${agents.length === 1 ? "agent" : "agents"}`
            : "All agents"}
        </h2>
        {agents === null ? (
          <div
            role="status"
            className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3"
          >
            <span className="sr-only">Loading agents…</span>
            {Array.from({ length: 6 }, (_, i) => (
              <div key={i} className="panel space-y-3 p-4">
                <div className="flex gap-3">
                  <Skeleton className="size-10" />
                  <div className="flex-1 space-y-2">
                    <Skeleton className="h-4 w-1/2" />
                    <Skeleton className="h-3 w-1/4" />
                  </div>
                </div>
                <Skeleton className="h-3 w-4/5" />
                <Skeleton className="h-3 w-3/5" />
              </div>
            ))}
          </div>
        ) : agents.length === 0 ? (
          <div className="rounded-lg border border-dashed border-line-bright px-6 py-12 text-center">
            <EmptyTicket />
            <p className="font-medium">No agents match</p>
            <p className="mt-1 text-sm text-muted">
              Try a different search or category, or post an open task and let routing find agents for you.
            </p>
          </div>
        ) : (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {agents.map((a, i) => (
              <Reveal key={a.id} index={i}>
                <AgentCard agent={a} />
              </Reveal>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}

export default function Page() {
  return (
    <AppShell>
      <RoleGuard role="CLIENT">
        <Marketplace />
      </RoleGuard>
    </AppShell>
  );
}
