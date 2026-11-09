"use client";

import { useEffect, useState } from "react";
import { api, ApiError, isPendingError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import { CountUp } from "@/components/CountUp";
import { EmptyTicket } from "@/components/EmptyTicket";
import { DisputeOutcomePanel } from "@/components/DisputeOutcomePanel";
import type { BuilderEarningsDTO, DisputeOutcomeDTO, PayoutDTO } from "@/lib/types";
import { SkeletonRows } from "@/components/ui";
import { PageHeader } from "@/components/PageHeader";

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
      <p role="alert" className="rounded-md border border-red/30 bg-red/10 px-3 py-2 text-xs text-red">
        {error}
      </p>
    );
  }

  if (!earnings) {
    return <SkeletonRows label="Loading…" rows={3} />;
  }

  // Ledger order: oldest first, so each line can carry the running total after it.
  const ledger = [...earnings.payouts]
    .sort((a, b) => new Date(a.settledAt).getTime() - new Date(b.settledAt).getTime())
    .reduce<{ p: PayoutDTO; total: number }[]>((acc, p) => {
      const prev = acc.length ? acc[acc.length - 1].total : 0;
      acc.push({ p, total: prev + p.amount });
      return acc;
    }, [])
    .reverse(); // show newest first

  return (
    <div className="space-y-10">
      <PageHeader
        title="Earnings"
        description="What your agents have earned, what is still held in escrow for them, and every payout as a ledger line."
      />

      {/* ── pay slip ─────────────────────────────────────────────────── */}
      <section aria-labelledby="payslip-h" className="docket overflow-hidden">
        <h2 id="payslip-h" className="sr-only">
          Pay slip
        </h2>
        <dl className="grid gap-px bg-line sm:grid-cols-[1.4fr_1fr_1fr]">
          <div className="bg-surface px-6 py-5">
            <dt className="text-sm text-muted">lifetime earned</dt>
            <dd className="display tabular mt-1 text-[3.25rem] !leading-none text-green">
              <CountUp value={earnings.lifetimeEarned} decimals={2} />
              <span className="ml-1.5 text-2xl text-muted">cr</span>
            </dd>
          </div>
          <div className="bg-surface px-6 py-5">
            <dt className="text-sm text-muted">pending · if accepted</dt>
            <dd className="display tabular mt-1 text-[2.25rem] !leading-none text-amber">{earnings.pendingIfAccepted.toFixed(2)}</dd>
            <p className="mt-1.5 text-xs text-dim">Held in escrow until the client accepts</p>
          </div>
          <div className="bg-surface px-6 py-5">
            <dt className="text-sm text-muted">paid tasks</dt>
            <dd className="display tabular mt-1 text-[2.25rem] !leading-none text-fg">{earnings.paidTaskCount}</dd>
          </div>
        </dl>
        <div className="docket-tear" />
        <p className="bg-surface-2 px-6 py-3 text-xs text-muted">
          Every accepted task pays you 85% of its price; the platform keeps 15%. A dispute settled as a split pays
          your share of it. Each figure here is read from the ledger, not estimated.
        </p>
      </section>

      {/* ── last 12 weeks ────────────────────────────────────────────── */}
      {earnings.payouts.length > 0 && <WeeklyPayouts payouts={earnings.payouts} />}

      {/* ── per-agent breakdown ──────────────────────────────────────── */}
      {earnings.perAgent.length > 0 && (
        <section className="space-y-3">
          <h2 className="eyebrow">By agent</h2>
          <div className="overflow-hidden rounded-xl border border-line bg-surface sm:overflow-x-auto">
            <table className="stack-table w-full text-sm sm:min-w-[32rem]">
              <thead>
                <tr className="border-b border-line bg-surface-2 text-left text-xs font-semibold text-muted">
                  <th scope="col" className="px-4 py-2.5 font-medium">Agent</th>
                  <th scope="col" className="px-4 py-2.5 text-right font-medium">Earned</th>
                  <th scope="col" className="px-4 py-2.5 text-right font-medium">In escrow</th>
                  <th scope="col" className="px-4 py-2.5 text-right font-medium">Paid tasks</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {earnings.perAgent.map((a) => (
                  <tr key={a.agentId} className="transition hover:bg-surface">
                    <td className="max-w-80 truncate px-4 py-2.5 font-medium text-fg">{a.agentName}</td>
                    <td data-label="Earned" className={`tabular px-4 py-2.5 text-right font-medium ${a.earned > 0 ? "text-fg" : "text-dim"}`}>
                      {a.earned.toFixed(2)}
                    </td>
                    <td data-label="In escrow" className={`tabular px-4 py-2.5 text-right font-medium ${a.pendingIfAccepted > 0 ? "text-amber" : "text-dim"}`}>
                      {a.pendingIfAccepted.toFixed(2)}
                    </td>
                    <td data-label="Paid tasks" className={`tabular px-4 py-2.5 text-right font-medium ${a.paidTaskCount > 0 ? "text-fg" : "text-dim"}`}>
                      {a.paidTaskCount}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {/* ── payout ledger ────────────────────────────────────────────── */}
      <section aria-labelledby="ledger-h" className="space-y-3">
        <h2 id="ledger-h" className="eyebrow">
          Payout ledger
        </h2>
        {earnings.payouts.length === 0 ? (
          <div className="rounded-lg border border-dashed border-line-bright px-6 py-12 text-center">
            <EmptyTicket />
            <p className="font-medium">No payouts yet</p>
            <p className="mt-1 text-sm text-muted">
              Earnings land here as a ledger line each time a client accepts your agent&apos;s work.
            </p>
          </div>
        ) : (
          <ol className="overflow-hidden rounded-xl border border-line bg-surface">
            {ledger.map(({ p, total }) => {
              const disputeEntry = disputes[p.taskId];
              const isOpen = expanded[p.taskId] ?? false;
              return (
                <li key={p.taskId} className="border-b border-line last:border-b-0">
                  <div className="grid grid-cols-[1fr_auto] items-center gap-x-4 gap-y-1 px-4 py-3 sm:grid-cols-[6.5rem_1fr_auto_7rem]">
                    <p className="tabular text-xs text-dim sm:order-none">
                      {new Date(p.settledAt).toLocaleDateString()}
                    </p>
                    <div className="col-span-2 min-w-0 sm:col-span-1">
                      <p className="truncate text-sm font-semibold text-fg">{p.taskTitle}</p>
                      <p className="mt-0.5 flex flex-wrap items-center gap-x-3 text-xs text-dim">
                        <span>{p.agentName}</span>
                        <button
                          onClick={() => handleToggle(p.taskId)}
                          className="font-medium text-muted underline-offset-4 transition-colors hover:text-accent hover:underline"
                          aria-expanded={isOpen}
                          aria-label={`${isOpen ? "Hide" : "Show"} settlement details and arbitration outcome`}
                        >
                          {isOpen ? "Hide details" : "Settlement details"}
                        </button>
                      </p>
                    </div>
                    <span className="tabular text-right text-sm font-semibold text-green">
                      +{p.amount.toFixed(2)} cr
                    </span>
                    <span className="tabular hidden text-right text-xs text-dim sm:block">
                      <span className="sr-only">Running total </span>
                      {total.toFixed(2)}
                    </span>
                  </div>

                  {isOpen && (
                    <div className="border-t border-line bg-surface-2 px-4 pb-3 pt-2">
                      {disputeEntry?.phase === "loading" && <p className="text-xs text-dim">Loading…</p>}
                      {disputeEntry?.phase === "found" && <DisputeOutcomePanel outcome={disputeEntry.outcome} />}
                      {disputeEntry?.phase === "none" && (
                        <p className="text-xs text-dim">No dispute. The client accepted the result and you were paid.</p>
                      )}
                      {disputeEntry?.phase === "error" && <p className="text-xs text-red">{disputeEntry.msg}</p>}
                    </div>
                  )}
                </li>
              );
            })}
          </ol>
        )}
        {earnings.payouts.length > 0 && (
          <p className="hidden text-right text-xs text-dim sm:block">Right-hand column: running total after each payout.</p>
        )}
      </section>
    </div>
  );
}

/** Payouts per week for the last 12 weeks, as a bar per week (newest on the right). */
function WeeklyPayouts({ payouts }: { payouts: PayoutDTO[] }) {
  const WEEKS = 12;
  const DAY = 86_400_000;
  // anchor on the most recent payout so the chart is stable and needs no clock
  const latest = Math.max(...payouts.map((p) => new Date(p.settledAt).getTime()));
  const weeks = Array.from({ length: WEEKS }, (_, i) => {
    const end = latest - (WEEKS - 1 - i) * 7 * DAY;
    const start = end - 7 * DAY;
    const amount = payouts
      .filter((p) => {
        const t = new Date(p.settledAt).getTime();
        return t > start && t <= end;
      })
      .reduce((sum, p) => sum + p.amount, 0);
    return { end, amount };
  });
  const max = Math.max(...weeks.map((w) => w.amount), 1);
  const total = weeks.reduce((s, w) => s + w.amount, 0);
  return (
    <section aria-labelledby="weeks-h" className="space-y-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 id="weeks-h" className="eyebrow">
          Paid out, 12 weeks to your latest payout
        </h2>
        <p className="tabular text-sm text-muted">{total.toFixed(2)} cr</p>
      </div>
      <div
        role="img"
        aria-label={`Weekly payouts, oldest to newest: ${weeks.map((w) => w.amount.toFixed(2)).join(", ")} credits`}
        className="flex h-28 items-end gap-1.5 rounded-xl border border-line bg-surface px-4 pb-3 pt-4"
      >
        {weeks.map((w, i) => (
          <div key={i} className="flex h-full flex-1 flex-col justify-end">
            <div
              className={`bar-rise rounded-t-sm ${w.amount > 0 ? "bg-green" : "bg-line"}`}
              style={{ height: w.amount > 0 ? `${Math.max(6, (w.amount / max) * 100)}%` : "3px", ["--i" as string]: i }}
              title={`${w.amount.toFixed(2)} cr, week ending ${new Date(w.end).toLocaleDateString()}`}
            />
          </div>
        ))}
      </div>
    </section>
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
