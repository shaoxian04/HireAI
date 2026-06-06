"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import { AgentCard } from "@/components/AgentCard";
import { CategoryBar } from "@/components/CategoryBar";
import { Button, Input } from "@/components/ui";
import type { AgentCardDTO, CatalogueSort, CategoryCountDTO } from "@/lib/types";

const SORTS: { value: CatalogueSort; label: string }[] = [
  { value: "hot", label: "🔥 Hot" },
  { value: "rating", label: "Top rated" },
  { value: "price_asc", label: "Price ↑" },
  { value: "price_desc", label: "Price ↓" },
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
    api<CategoryCountDTO[]>("/catalogue/categories").then(setCategories).catch(() => {});
  }, []);

  useEffect(() => {
    const t = setTimeout(() => {
      const params = new URLSearchParams({ q, category, sort });
      api<AgentCardDTO[]>(`/catalogue/agents?${params}`)
        .then(setAgents)
        .catch((e) => setError(e instanceof ApiError ? e.message : "Failed to load agents"));
    }, q ? 250 : 0);
    return () => clearTimeout(t);
  }, [q, category, sort]);

  const featured = useMemo(() => (agents ?? []).filter((a) => a.featured), [agents]);

  return (
    <div className="space-y-8">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="eyebrow flex items-center gap-2">
            <span className="inline-block h-px w-6 bg-accent" />
            Marketplace
          </p>
          <h1 className="mt-3 text-3xl font-extrabold tracking-tight">Hire an agent</h1>
          <p className="mt-2 text-sm text-muted">
            Browse verified agents, inspect their output contract, and book directly — escrow
            protects every credit.
          </p>
        </div>
        <Link href="/client/tasks/new">
          <Button variant="secondary">+ Submit open task</Button>
        </Link>
      </header>

      {/* search + sort */}
      <div className="flex flex-wrap items-center gap-3">
        <div className="min-w-64 flex-1">
          <Input
            placeholder="Search agents or builders…"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            aria-label="Search agents or builders"
          />
        </div>
        <div className="flex gap-1.5">
          {SORTS.map((s) => (
            <button
              key={s.value}
              type="button"
              aria-pressed={sort === s.value}
              onClick={() => setSort(s.value)}
              className={`rounded-md border px-2.5 py-1.5 font-mono text-[0.65rem] uppercase tracking-wider transition ${
                sort === s.value
                  ? "border-accent/60 bg-accent/15 text-accent"
                  : "border-line bg-surface-2 text-muted hover:text-fg"
              }`}
            >
              {s.label}
            </button>
          ))}
        </div>
      </div>

      <CategoryBar categories={categories} active={category} onSelect={setCategory} />

      {error && (
        <p role="alert" className="rounded-md border border-red/30 bg-red/10 px-3 py-2 font-mono text-xs text-red">
          {error}
        </p>
      )}

      {/* hot strip (only on unfiltered hot view) */}
      {sort === "hot" && !q && !category && featured.length > 0 && (
        <section aria-label="Hot agents">
          <p className="eyebrow mb-3">🔥 Hot right now</p>
          <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
            {featured.map((a) => (
              <AgentCard key={a.id} agent={a} />
            ))}
          </div>
        </section>
      )}

      {/* grid */}
      <section aria-label="All agents">
        <p className="eyebrow mb-3">All agents</p>
        {agents === null ? (
          <p className="font-mono text-sm text-dim">Scanning the registry…</p>
        ) : agents.length === 0 ? (
          <div className="panel p-10 text-center">
            <p className="font-mono text-sm text-muted">No agents match.</p>
            <p className="mt-1 font-mono text-xs text-dim">Try a different search or category.</p>
          </div>
        ) : (
          <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
            {agents.map((a) => (
              <AgentCard key={a.id} agent={a} />
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
