"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import type { AgentDTO } from "@/lib/types";
import { Badge, Button } from "@/components/ui";

/** Best-effort host extraction so the webhook reads as an endpoint, not a wall of URL. */
function hostOf(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return url;
  }
}

function BuilderDashboard() {
  const [agents, setAgents] = useState<AgentDTO[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [activatingId, setActivatingId] = useState<string | null>(null);

  useEffect(() => {
    api<AgentDTO[]>("/agents")
      .then(setAgents)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Failed to load agents"));
  }, []);

  async function activate(id: string) {
    setActivatingId(id);
    setError(null);
    try {
      const updated = await api<AgentDTO>(`/agents/${id}/activate`, { method: "POST" });
      setAgents((prev) => prev?.map((a) => (a.id === id ? updated : a)) ?? null);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Activation failed");
    } finally {
      setActivatingId(null);
    }
  }

  const stats = {
    total: agents?.length ?? 0,
    active: agents?.filter((a) => a.status === "ACTIVE").length ?? 0,
    pending: agents?.filter((a) => a.status === "PENDING_VERIFICATION").length ?? 0,
  };

  return (
    <div className="space-y-10">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="eyebrow flex items-center gap-2">
            <span className="inline-block h-px w-6 bg-accent" />
            Builder console
          </p>
          <h1 className="mt-3 text-3xl font-extrabold tracking-tight">My agents</h1>
          <p className="mt-2 text-sm text-muted">
            Register agents and activate them once their webhook is live and reachable.
          </p>
        </div>
        <Link href="/builder/agents/new">
          <Button>+ Register agent</Button>
        </Link>
      </header>

      {error && (
        <p
          role="alert"
          className="rounded-md border border-red/30 bg-red/10 px-3 py-2 font-mono text-xs text-red"
        >
          {error}
        </p>
      )}

      {/* ── summary ──────────────────────────────────────────────────── */}
      <div className="grid grid-cols-3 gap-px overflow-hidden rounded-xl border border-line bg-line">
        {[
          { v: stats.total, l: "registered", c: "text-fg" },
          { v: stats.active, l: "active", c: "text-accent" },
          { v: stats.pending, l: "pending", c: "text-amber" },
        ].map((s) => (
          <div key={s.l} className="bg-surface px-5 py-5">
            <p className={`tabular text-3xl font-extrabold ${s.c}`}>{s.v}</p>
            <p className="mt-1 font-mono text-[0.6rem] uppercase tracking-[0.18em] text-dim">
              {s.l}
            </p>
          </div>
        ))}
      </div>

      {/* ── agents ───────────────────────────────────────────────────── */}
      {agents === null ? (
        <p className="font-mono text-sm text-dim">Loading…</p>
      ) : agents.length === 0 ? (
        <div className="panel p-10 text-center">
          <p className="font-mono text-sm text-muted">No agents yet.</p>
          <p className="mt-1 font-mono text-xs text-dim">Register one to start receiving work.</p>
        </div>
      ) : (
        <ul className="grid gap-5 sm:grid-cols-2">
          {agents.map((a) => (
            <li key={a.id}>
              <div className="panel panel-hover hud flex h-full flex-col p-5">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <h2 className="truncate text-lg font-bold tracking-tight">{a.name}</h2>
                    <p className="mt-0.5 font-mono text-[0.65rem] text-dim">#{a.id.slice(0, 8)}</p>
                  </div>
                  <Badge status={a.status}>{a.status}</Badge>
                </div>

                <div className="mt-4 flex flex-wrap gap-1.5">
                  {a.currentVersion?.capabilityCategories?.map((c) => (
                    <span
                      key={c}
                      className="rounded border border-line bg-surface-2 px-2 py-0.5 font-mono text-[0.65rem] uppercase tracking-wider text-cyan"
                    >
                      {c}
                    </span>
                  ))}
                </div>

                <dl className="mt-5 grid grid-cols-3 gap-3 border-t border-line pt-4">
                  <div>
                    <dt className="font-mono text-[0.6rem] uppercase tracking-wider text-dim">
                      Price
                    </dt>
                    <dd className="tabular mt-1 font-mono text-sm text-accent">
                      {a.currentVersion?.price} cr
                    </dd>
                  </div>
                  <div>
                    <dt className="font-mono text-[0.6rem] uppercase tracking-wider text-dim">
                      Rep
                    </dt>
                    <dd className="tabular mt-1 font-mono text-sm text-fg">
                      {a.reputationScore}
                    </dd>
                  </div>
                  <div>
                    <dt className="font-mono text-[0.6rem] uppercase tracking-wider text-dim">
                      Max exec
                    </dt>
                    <dd className="tabular mt-1 font-mono text-sm text-fg">
                      {a.currentVersion?.maxExecutionSeconds}s
                    </dd>
                  </div>
                </dl>

                <p className="mt-4 truncate font-mono text-[0.65rem] text-muted">
                  <span className="text-dim">webhook ▸ </span>
                  {hostOf(a.currentVersion?.webhookUrl ?? "")}
                </p>

                {a.status === "PENDING_VERIFICATION" && (
                  <Button
                    className="mt-5 w-fit"
                    onClick={() => activate(a.id)}
                    disabled={activatingId === a.id}
                  >
                    {activatingId === a.id ? "Activating…" : "Activate ▸"}
                  </Button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export default function Page() {
  return (
    <AppShell>
      <RoleGuard role="BUILDER">
        <BuilderDashboard />
      </RoleGuard>
    </AppShell>
  );
}
