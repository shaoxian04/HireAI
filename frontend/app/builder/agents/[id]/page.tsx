"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import type { AgentDTO, AgentProfileViewDTO } from "@/lib/types";
import { Badge } from "@/components/ui/Badge";
import { Button } from "@/components/ui/Button";
import { TabStorefront } from "@/components/manage/TabStorefront";
import { TabPricing } from "@/components/manage/TabPricing";
import { TabStats } from "@/components/manage/TabStats";
import { TabReviews } from "@/components/manage/TabReviews";
import { TabReputation } from "@/components/manage/TabReputation";
import { SkeletonRows, ConfirmDialog } from "@/components/ui";

type Tab = "storefront" | "pricing" | "stats" | "reputation" | "reviews";

const TABS: { id: Tab; label: string }[] = [
  { id: "storefront", label: "Storefront" },
  { id: "pricing", label: "Pricing" },
  { id: "stats", label: "Stats" },
  { id: "reputation", label: "Reputation" },
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
  const [reputationLoaded, setReputationLoaded] = useState(false);
  const [reviewsLoaded, setReviewsLoaded] = useState(false);
  const [transitioning, setTransitioning] = useState(false);
  const [confirming, setConfirming] = useState<"suspend" | "deactivate" | null>(null);

  async function transition(verb: "suspend" | "reactivate" | "deactivate") {
    setTransitioning(true);
    setLoadError(null);
    try {
      const updated = await api<AgentDTO>(`/agents/${id}/${verb}`, { method: "POST" });
      setAgent(updated);
      setConfirming(null);
    } catch (e) {
      setLoadError(e instanceof ApiError ? e.message : "Action failed");
    } finally {
      setTransitioning(false);
    }
  }

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
    if (tab === "reputation") setReputationLoaded(true);
    if (tab === "reviews") setReviewsLoaded(true);
  }

  if (loadError) {
    return (
      <p role="alert" className="text-xs text-red">
        {loadError}
      </p>
    );
  }

  if (!agent || !profile) {
    return <SkeletonRows label="Loading…" rows={3} />;
  }

  return (
    <div className="mx-auto max-w-4xl space-y-8">
      {/* Header */}
      <header>
        <Link
          href="/builder"
          className="text-sm font-medium text-muted transition hover:text-accent"
        >
          ← My agents
        </Link>
        <div className="mt-4 flex flex-wrap items-center gap-3">
          <h1 className="display text-[2.6rem]">{agent.name}</h1>
          <Badge status={agent.status} />
          <div className="ml-auto flex gap-2">
            {agent.status === "ACTIVE" && (
              <Button variant="secondary" disabled={transitioning} onClick={() => setConfirming("suspend")}>
                Suspend
              </Button>
            )}
            {agent.status === "SUSPENDED" && (
              <Button variant="secondary" disabled={transitioning} onClick={() => transition("reactivate")}>
                Reactivate
              </Button>
            )}
            {(agent.status === "ACTIVE" || agent.status === "SUSPENDED") && (
              <Button variant="danger" disabled={transitioning} onClick={() => setConfirming("deactivate")}>
                Deactivate
              </Button>
            )}
          </div>
        </div>
        <p className="mt-1 font-mono text-xs text-dim">
          public page{" "}
          <Link
            href={`/client/agents/${id}`}
            className="transition hover:text-accent"
          >
            /client/agents/{id}
          </Link>
        </p>
      </header>

      {/* Tab bar */}
      {/* Five tabs do not fit a phone; scroll the strip rather than the whole page sideways. */}
      <div
        role="tablist"
        aria-label="Agent management sections"
        className="flex gap-1 overflow-x-auto border-b border-line"
        onKeyDown={(e) => {
          // Arrow keys move between tabs (and select), the standard tablist pattern.
          if (e.key !== "ArrowRight" && e.key !== "ArrowLeft" && e.key !== "Home" && e.key !== "End") return;
          e.preventDefault();
          const i = TABS.findIndex((t) => t.id === activeTab);
          const next =
            e.key === "Home" ? 0 : e.key === "End" ? TABS.length - 1 : (i + (e.key === "ArrowRight" ? 1 : -1) + TABS.length) % TABS.length;
          handleTabChange(TABS[next].id);
          document.getElementById(`tab-${TABS[next].id}`)?.focus();
        }}
      >
        {TABS.map((t) => (
          <button
            key={t.id}
            id={`tab-${t.id}`}
            type="button"
            role="tab"
            aria-selected={activeTab === t.id}
            aria-controls="agent-tabpanel"
            tabIndex={activeTab === t.id ? 0 : -1}
            onClick={() => handleTabChange(t.id)}
            className={`shrink-0 px-4 pb-2 text-sm font-medium transition ${
              activeTab === t.id ? "border-b-2 border-accent text-accent" : "text-muted hover:text-fg"
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      <ConfirmDialog
        open={confirming === "suspend"}
        title={`Suspend ${agent.name}?`}
        confirmLabel="Suspend agent"
        busy={transitioning}
        onConfirm={() => transition("suspend")}
        onCancel={() => setConfirming(null)}
      >
        It stops receiving new work straight away. Tasks already running finish normally. You can
        reactivate it at any time.
      </ConfirmDialog>
      <ConfirmDialog
        open={confirming === "deactivate"}
        title={`Deactivate ${agent.name}?`}
        confirmLabel="Deactivate agent"
        danger
        busy={transitioning}
        onConfirm={() => transition("deactivate")}
        onCancel={() => setConfirming(null)}
      >
        It comes off the marketplace for good and can&apos;t be reactivated. Its reviews and
        earnings history stay on record.
      </ConfirmDialog>

      {/* Tab panels */}
      <div id="agent-tabpanel" role="tabpanel" aria-labelledby={`tab-${activeTab}`}>
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
        {activeTab === "reputation" && reputationLoaded && <TabReputation agentId={id} />}
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
