"use client";

import { useEffect, useState, type FormEvent } from "react";
import Link from "next/link";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import { StatusTrack } from "@/components/StatusTrack";
import type { TaskDTO, TopupRequest, WalletDTO } from "@/lib/types";
import { Badge, Button, Input } from "@/components/ui";

function ClientTasks() {
  const [wallet, setWallet] = useState<WalletDTO | null>(null);
  const [tasks, setTasks] = useState<TaskDTO[] | null>(null);
  const [topupAmount, setTopupAmount] = useState(50);
  const [error, setError] = useState<string | null>(null);
  const [toppingUp, setToppingUp] = useState(false);

  useEffect(() => {
    function showError(e: unknown) {
      setError(e instanceof ApiError ? e.message : "Failed to load");
    }
    api<WalletDTO>("/wallet").then(setWallet).catch(showError);
    api<TaskDTO[]>("/tasks").then(setTasks).catch(showError);
  }, []);

  async function topup(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setToppingUp(true);
    try {
      const body: TopupRequest = { amount: topupAmount };
      const updated = await api<WalletDTO>("/wallet/topup", {
        method: "POST",
        body: JSON.stringify(body),
      });
      setWallet(updated);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Top-up failed");
    } finally {
      setToppingUp(false);
    }
  }

  const total = wallet ? wallet.availableBalance + wallet.escrowBalance : 0;
  const availablePct = total > 0 ? (wallet!.availableBalance / total) * 100 : 0;

  return (
    <div className="space-y-10">
      <header>
        <p className="eyebrow flex items-center gap-2">
          <span className="inline-block h-px w-6 bg-accent" />
          Client console
        </p>
        <h1 className="mt-3 text-3xl font-extrabold tracking-tight">Treasury &amp; tasks</h1>
        <p className="mt-2 text-sm text-muted">
          Credits fund tasks; submitting one freezes its budget in escrow until the work is accepted.
        </p>
      </header>

      {error && (
        <p
          role="alert"
          className="rounded-md border border-red/30 bg-red/10 px-3 py-2 font-mono text-xs text-red"
        >
          {error}
        </p>
      )}

      {/* ── balance instrument ───────────────────────────────────────── */}
      <section className="panel hud p-6">
        <div className="flex items-center justify-between border-b border-line pb-3">
          <span className="eyebrow">Wallet</span>
          <span className="font-mono text-[0.65rem] uppercase tracking-wider text-dim">
            balance · credits
          </span>
        </div>

        {wallet === null ? (
          <p className="py-6 font-mono text-sm text-dim">Loading wallet…</p>
        ) : (
          <>
            <div className="mt-6 flex flex-wrap items-end justify-between gap-6">
              <div className="flex flex-wrap gap-12">
                <div>
                  <p className="font-mono text-[0.65rem] uppercase tracking-[0.18em] text-muted">
                    Available
                  </p>
                  <p className="tabular mt-1 text-4xl font-extrabold text-accent">
                    {wallet.availableBalance}
                  </p>
                </div>
                <div>
                  <p className="font-mono text-[0.65rem] uppercase tracking-[0.18em] text-muted">
                    In escrow
                  </p>
                  <p className="tabular mt-1 text-4xl font-extrabold text-amber">
                    {wallet.escrowBalance}
                  </p>
                </div>
                <div>
                  <p className="font-mono text-[0.65rem] uppercase tracking-[0.18em] text-muted">
                    Total
                  </p>
                  <p className="tabular mt-1 text-4xl font-extrabold text-fg">{total}</p>
                </div>
              </div>

              <form onSubmit={topup} className="flex items-end gap-2">
                <div>
                  <label
                    htmlFor="topup"
                    className="mb-1.5 block font-mono text-[0.65rem] uppercase tracking-[0.18em] text-muted"
                  >
                    Top up
                  </label>
                  <Input
                    id="topup"
                    type="number"
                    min={1}
                    value={topupAmount}
                    aria-label="Top-up amount"
                    onChange={(e) => setTopupAmount(Number(e.target.value))}
                    className="w-28"
                  />
                </div>
                <Button type="submit" disabled={toppingUp}>
                  {toppingUp ? "…" : "Add"}
                </Button>
              </form>
            </div>

            {/* available / escrow ratio bar */}
            <div className="mt-6">
              <div className="flex h-2 overflow-hidden rounded-full bg-surface-2">
                <div className="bg-accent" style={{ width: `${availablePct}%` }} />
                <div className="bg-amber" style={{ width: `${100 - availablePct}%` }} />
              </div>
              <div className="mt-2 flex justify-between font-mono text-[0.6rem] uppercase tracking-wider text-dim">
                <span>available</span>
                <span>escrow</span>
              </div>
            </div>
          </>
        )}
      </section>

      {/* ── tasks ────────────────────────────────────────────────────── */}
      <section className="space-y-4">
        <header className="flex items-end justify-between">
          <div>
            <h2 className="text-xl font-extrabold tracking-tight">Tasks</h2>
            <p className="mt-1 font-mono text-xs text-dim">
              {tasks ? `${tasks.length} total` : "…"}
            </p>
          </div>
          <Link href="/client/tasks/new">
            <Button>+ Submit task</Button>
          </Link>
        </header>

        {tasks === null ? (
          <p className="font-mono text-sm text-dim">Loading tasks…</p>
        ) : tasks.length === 0 ? (
          <div className="panel p-10 text-center">
            <p className="font-mono text-sm text-muted">No tasks yet.</p>
            <p className="mt-1 font-mono text-xs text-dim">
              Submit one and watch it travel the pipeline.
            </p>
          </div>
        ) : (
          <ul className="overflow-hidden rounded-xl border border-line">
            {tasks.map((t, i) => (
              <li key={t.id}>
                <Link
                  href={`/client/tasks/${t.id}`}
                  className={`group flex flex-wrap items-center justify-between gap-4 bg-surface px-5 py-4 transition hover:bg-surface-2 ${
                    i > 0 ? "border-t border-line" : ""
                  }`}
                >
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-3">
                      <span className="truncate font-medium text-fg group-hover:text-accent">
                        {t.title}
                      </span>
                      <Badge status={t.status}>{t.status}</Badge>
                    </div>
                    <p className="mt-1 font-mono text-xs text-dim">
                      <span className="tabular text-muted">{t.budget}</span> cr · #
                      {t.id.slice(0, 6)}
                    </p>
                  </div>
                  <div className="hidden w-64 shrink-0 sm:block">
                    <StatusTrack status={t.status} />
                  </div>
                  <span className="font-mono text-muted transition group-hover:translate-x-0.5 group-hover:text-accent">
                    →
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

export default function Page() {
  return (
    <AppShell>
      <RoleGuard role="CLIENT">
        <ClientTasks />
      </RoleGuard>
    </AppShell>
  );
}
