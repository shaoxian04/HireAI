"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import type { AgentDTO, AgentProfileViewDTO } from "@/lib/types";
import { Badge } from "@/components/ui/Badge";
import { TabStorefront } from "@/components/manage/TabStorefront";
import { TabPricing } from "@/components/manage/TabPricing";
import { TabStats } from "@/components/manage/TabStats";
import { TabReviews } from "@/components/manage/TabReviews";

type Tab = "storefront" | "pricing" | "stats" | "reviews";

const TABS: { id: Tab; label: string }[] = [
  { id: "storefront", label: "Storefront" },
  { id: "pricing", label: "Pricing" },
  { id: "stats", label: "Stats" },
  { id: "reviews", label: "Reviews" },
];

function ManageAgentPage() {
  const params = useParams();
  const id = params.id as string;

  const [agent, setAgent] = useState<AgentDTO | null>(null);
  const [profile, setProfile] = useState<AgentProfileViewDTO | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [activeTab, setActiveTab] = useState<Tab>("storefront");
  const [statsLoaded, setStatsLoaded] = useState(false);
  const [reviewsLoaded, setReviewsLoaded] = useState(false);

  // Load agent + profile in parallel on mount
  useEffect(() => {
    Promise.all([
      api<AgentDTO>(`/agents/${id}`),
      api<AgentProfileViewDTO>(`/agents/${id}/profile`),
    ])
      .then(([a, p]) => {
        setAgent(a);
        setProfile(p);
      })
      .catch((e) => setLoadError(e instanceof ApiError ? e.message : "Failed to load agent"));
  }, [id]);

  function handleTabChange(tab: Tab) {
    setActiveTab(tab);
    if (tab === "stats") setStatsLoaded(true);
    if (tab === "reviews") setReviewsLoaded(true);
  }

  if (loadError) {
    return (
      <p role="alert" className="font-mono text-xs text-red">
        {loadError}
      </p>
    );
  }

  if (!agent || !profile) {
    return <p className="font-mono text-sm text-dim">Loading…</p>;
  }

  return (
    <div className="space-y-8">
      {/* Header */}
      <header>
        <Link
          href="/builder"
          className="font-mono text-xs text-dim transition hover:text-accent"
        >
          ← my agents
        </Link>
        <div className="mt-4 flex flex-wrap items-center gap-3">
          <h1 className="text-3xl font-extrabold tracking-tight">{agent.name}</h1>
          <Badge status={agent.status}>{agent.status}</Badge>
        </div>
        <p className="mt-1 font-mono text-[0.65rem] text-dim">
          public page ▸{" "}
          <Link
            href={`/client/agents/${id}`}
            className="transition hover:text-accent"
          >
            /client/agents/{id}
          </Link>
        </p>
      </header>

      {/* Tab bar */}
      <div
        role="tablist"
        className="flex gap-1 border-b border-line"
        aria-label="Agent management sections"
      >
        {TABS.map((t) => (
          <button
            key={t.id}
            role="tab"
            aria-selected={activeTab === t.id}
            onClick={() => handleTabChange(t.id)}
            className={`px-4 pb-2 font-mono text-xs uppercase tracking-wider transition ${
              activeTab === t.id
                ? "border-b-2 border-accent text-accent"
                : "text-muted hover:text-fg"
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {/* Tab panels */}
      <div role="tabpanel">
        {activeTab === "storefront" && (
          <TabStorefront
            agentId={id}
            profile={profile}
            onProfileChange={setProfile}
          />
        )}
        {activeTab === "pricing" && (
          <TabPricing agentId={id} agent={agent} onAgentChange={setAgent} />
        )}
        {activeTab === "stats" && statsLoaded && <TabStats agentId={id} />}
        {activeTab === "reviews" && reviewsLoaded && <TabReviews agentId={id} />}
      </div>
    </div>
  );
}

export default function Page() {
  return (
    <AppShell>
      <RoleGuard role="BUILDER">
        <ManageAgentPage />
      </RoleGuard>
    </AppShell>
  );
}
