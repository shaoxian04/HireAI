"use client";

import { useEffect, useState } from "react";
import { api, ApiError, isPendingError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import { StatTile } from "@/components/StatTile";
import { DisputeOutcomePanel } from "@/components/DisputeOutcomePanel";
import type { BuilderEarningsDTO, DisputeOutcomeDTO } from "@/lib/types";

// ── per-payout dispute state ──────────────────────────────────────────────────
// Limitation: disputes that resolved to a FULL REFUND produce no payout row, so
// they are not discoverable here. A builder dispute-list endpoint would surface
// them — that is deferred to a future task.
type DisputeEntry =
  | { phase: "loading" }
  | { phase: "found"; outcome: DisputeOutcomeDTO }
  | { phase: "none" } // 404 — task was never disputed
  | { phase: "error"; msg: string };

function EarningsView() {
  const [earnings, setEarnings] = useState<BuilderEarningsDTO | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Cached dispute results keyed by taskId
  const [disputes, setDisputes] = useState<Record<string, DisputeEntry>>({});
  // Which payout rows are currently expanded
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});

  useEffect(() => {
    api<BuilderEarningsDTO>("/builder/earnings")
      .then(setEarnings)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Failed to load earnings"));
  }, []);

  // Fetch is lazy — triggered by the user expanding a row, not on mount.
  // This avoids N API calls on page load and is clear of react-hooks/set-state-in-effect.
  function handleToggle(taskId: string) {
    const isOpen = expanded[taskId] ?? false;
    setExpanded((prev) => ({ ...prev, [taskId]: !isOpen }));

    // Only fetch on first expand; subsequent toggles reuse the cached result
    if (!isOpen && !disputes[taskId]) {
      setDisputes((prev) => ({ ...prev, [taskId]: { phase: "loading" } }));
      api<DisputeOutcomeDTO>(`/disputes/by-task/${taskId}`)
        .then((outcome) =>
          setDisputes((prev) => ({ ...prev, [taskId]: { phase: "found", outcome } })),
        )
        .catch((e) => {
          if (isPendingError(e)) {
            // 404 → no dispute opened on this task; most settled payouts land here
            setDisputes((prev) => ({ ...prev, [taskId]: { phase: "none" } }));
          } else {
            setDisputes((prev) => ({
              ...prev,
              [taskId]: {
                phase: "error",
                msg: e instanceof ApiError ? e.message : "Failed to load dispute",
              },
            }));
          }
        });
    }
  }

  if (error) {
    return (
      <p role="alert" className="rounded-md border border-red/30 bg-red/10 px-3 py-2 font-mono text-xs text-red">
        {error}
      </p>
    );
  }

  if (!earnings) {
    return <p className="font-mono text-sm text-dim">Loading…</p>;
  }

  return (
    <div className="space-y-10">
      <header>
        <p className="eyebrow flex items-center gap-2">
          <span className="inline-block h-px w-6 bg-accent" />
          Builder console
        </p>
        <h1 className="mt-3 text-3xl font-extrabold tracking-tight">Earnings</h1>
        <p className="mt-2 text-sm text-muted">
          Accepted work pays out 85% of the task budget; amounts of record live in the ledger.
        </p>
      </header>

      {/* ── summary ──────────────────────────────────────────────────── */}
      <div className="grid grid-cols-3 gap-px overflow-hidden rounded-xl border border-line bg-line">
        <StatTile value={earnings.lifetimeEarned.toFixed(2)} label="lifetime earned" tone="accent" />
        <StatTile value={earnings.pendingIfAccepted.toFixed(2)} label="pending · if accepted" tone="amber" />
        <StatTile value={earnings.paidTaskCount} label="paid tasks" />
      </div>

      {/* ── per-agent breakdown ──────────────────────────────────────── */}
      {earnings.perAgent.length > 0 && (
        <div>
          <p className="eyebrow mb-3">By agent</p>
          <ul className="space-y-2">
            {earnings.perAgent.map((a) => (
              <li
                key={a.agentId}
                className="flex items-center justify-between gap-3 rounded-md border border-line bg-surface-2 px-4 py-3"
              >
                <p className="truncate text-sm font-bold text-fg">{a.agentName}</p>
                <dl className="flex shrink-0 items-center gap-6 font-mono text-xs">
                  <div className="text-right">
                    <dt className="text-[0.6rem] uppercase tracking-wider text-dim">earned</dt>
                    <dd className="tabular mt-0.5 text-accent">{a.earned.toFixed(2)} cr</dd>
                  </div>
                  <div className="text-right">
                    <dt className="text-[0.6rem] uppercase tracking-wider text-dim">pending</dt>
                    <dd className="tabular mt-0.5 text-amber">{a.pendingIfAccepted.toFixed(2)} cr</dd>
                  </div>
                  <div className="text-right">
                    <dt className="text-[0.6rem] uppercase tracking-wider text-dim">paid tasks</dt>
                    <dd className="tabular mt-0.5 text-fg">{a.paidTaskCount}</dd>
                  </div>
                </dl>
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* ── payout history ───────────────────────────────────────────── */}
      <div>
        <p className="eyebrow mb-3">Payout history</p>
        {earnings.payouts.length === 0 ? (
          <div className="panel p-10 text-center">
            <p className="font-mono text-sm text-muted">
              No payouts yet — earnings land here when a client accepts your agent&apos;s work.
            </p>
          </div>
        ) : (
          <ul className="space-y-2">
            {earnings.payouts.map((p) => {
              const disputeEntry = disputes[p.taskId];
              const isOpen = expanded[p.taskId] ?? false;
              return (
                <li
                  key={p.taskId}
                  className="overflow-hidden rounded-md border border-line bg-surface-2"
                >
                  {/* ── row header ─────────────────────────────────── */}
                  <div className="flex items-center justify-between gap-3 px-4 py-3">
                    <div className="min-w-0">
                      <p className="truncate text-sm text-fg">{p.taskTitle}</p>
                      <p className="mt-0.5 font-mono text-[0.65rem] text-dim">
                        {p.agentName} · {new Date(p.settledAt).toLocaleDateString()}
                      </p>
                    </div>
                    <div className="flex shrink-0 items-center gap-3">
                      <button
                        onClick={() => handleToggle(p.taskId)}
                        className="font-mono text-[0.65rem] text-dim transition-colors hover:text-fg"
                        aria-expanded={isOpen}
                      >
                        {isOpen ? "▲" : "▼"} Arbitration outcome
                      </button>
                      <span className="tabular font-mono text-sm font-semibold text-accent">
                        +{p.amount.toFixed(2)} cr
                      </span>
                    </div>
                  </div>

                  {/* ── expandable dispute panel ────────────────────── */}
                  {isOpen && (
                    <div className="border-t border-line px-4 pb-3 pt-2">
                      {disputeEntry?.phase === "loading" && (
                        <p className="font-mono text-xs text-dim">Loading…</p>
                      )}
                      {disputeEntry?.phase === "found" && (
                        <DisputeOutcomePanel outcome={disputeEntry.outcome} />
                      )}
                      {/* phase "none" (404) → task was never disputed; show nothing */}
                      {disputeEntry?.phase === "error" && (
                        <p className="font-mono text-xs text-red">{disputeEntry.msg}</p>
                      )}
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </div>
  );
}

export default function Page() {
  return (
    <AppShell>
      <RoleGuard role="BUILDER">
        <EarningsView />
      </RoleGuard>
    </AppShell>
  );
}
