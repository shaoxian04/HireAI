"use client";

import { useEffect, useState, type FormEvent } from "react";
import Link from "next/link";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import { StatusTrack } from "@/components/StatusTrack";
import type { TaskDTO, TopupRequest, WalletDTO } from "@/lib/types";
import { Badge, Button, Input, Skeleton, SkeletonRows, statusLabel, ButtonLink } from "@/components/ui";
import { PageHeader } from "@/components/PageHeader";
import { CountUp } from "@/components/CountUp";
import { moneyState, type MoneyState } from "@/lib/format";
import { EmptyTicket } from "@/components/EmptyTicket";

/** Escrow is the accent; paid is green; money back to the client is neutral. */
const MONEY_TONE: Record<MoneyState["tone"], string> = {
  held: "font-medium text-accent",
  paid: "text-green",
  back: "text-muted",
  split: "text-amber",
};

function ClientTasks() {
  const [wallet, setWallet] = useState<WalletDTO | null>(null);
  const [tasks, setTasks] = useState<TaskDTO[] | null>(null);
  const [topupAmount, setTopupAmount] = useState("50");
  const [topupError, setTopupError] = useState<string | null>(null);
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
    setTopupError(null);
    const amount = topupAmount.trim() === "" ? NaN : Number(topupAmount);
    if (Number.isNaN(amount) || amount < 1) {
      setTopupError("Enter how many credits to add, 1 or more.");
      document.getElementById("topup")?.focus();
      return;
    }
    setToppingUp(true);
    try {
      const body: TopupRequest = { amount };
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
      <PageHeader
        title="My tasks"
        description="Submitting a task freezes its budget in escrow until you accept the result or a dispute settles it."
        actions={
          <ButtonLink href="/client/tasks/new">Submit task</ButtonLink>
        }
      />

      {error && (
        <p
          role="alert"
          className="rounded-md border border-red/30 bg-red/10 px-3 py-2 text-sm text-red"
        >
          {error}
        </p>
      )}

      {/* ── wallet ───────────────────────────────────────────────────── */}
      <section
        aria-labelledby="wallet-heading"
        className="docket overflow-hidden"
      >
        <div className="p-6 sm:p-7">
          <div className="flex flex-wrap items-start justify-between gap-6">
            <div className="space-y-4">
              <h2 id="wallet-heading" className="eyebrow">
                Wallet
              </h2>
              {wallet === null ? (
                <div role="status" className="flex gap-10">
                  <span className="sr-only">Loading wallet…</span>
                  <Skeleton className="h-8 w-24" />
                  <Skeleton className="h-8 w-20" />
                  <Skeleton className="h-8 w-20" />
                </div>
              ) : (
                <dl className="flex flex-wrap gap-x-12 gap-y-3">
                  <div>
                    <dt className="text-xs text-dim">Available</dt>
                    <dd className="display tabular mt-1 text-[3.25rem] text-fg">
                      <CountUp value={wallet.availableBalance} />
                    </dd>
                  </div>
                  <div>
                    <dt className="flex items-center gap-1.5 text-xs text-dim">
                      <span
                        className="size-2 rounded-full bg-accent"
                        aria-hidden
                      />
                      In escrow
                    </dt>
                    <dd className="display tabular mt-1 text-[3.25rem] text-accent">
                      <CountUp value={wallet.escrowBalance} />
                    </dd>
                  </div>
                  <div>
                    <dt className="text-xs text-dim">Total credits</dt>
                    <dd className="display tabular mt-1 text-[3.25rem] text-dim">
                      <CountUp value={total} />
                    </dd>
                  </div>
                </dl>
              )}
            </div>
          </div>

          {wallet !== null && total > 0 && (
            <div
              className="mt-6 flex h-2 overflow-hidden rounded-full bg-surface-2"
              role="img"
              aria-label={`${Math.round(availablePct)}% available, ${Math.round(100 - availablePct)}% in escrow`}
            >
              <div className="bg-fg/80" style={{ width: `${availablePct}%` }} />
              <div
                className="bg-accent"
                style={{ width: `${100 - availablePct}%` }}
              />
            </div>
          )}
        </div>
        <div className="docket-tear" />
        <form
          onSubmit={topup}
          noValidate
          className="flex flex-wrap items-end gap-3 bg-surface-2 px-6 py-4 sm:px-7"
        >
          <div className="space-y-1.5">
            <label
              htmlFor="topup"
              className="block text-xs font-semibold text-muted"
            >
              Add credits to your wallet
            </label>
            <Input
              id="topup"
              type="number"
              min={1}
              value={topupAmount}
              aria-invalid={topupError ? true : undefined}
              aria-describedby={topupError ? "topup-error" : undefined}
              onChange={(e) => {
                setTopupAmount(e.target.value);
                setTopupError(null);
              }}
              placeholder="50"
              className="w-28 font-mono"
            />
          </div>
          <Button type="submit" disabled={toppingUp}>
            {toppingUp ? "Adding…" : "Add credits"}
          </Button>
          {topupError && (
            <p id="topup-error" role="alert" className="basis-full text-xs font-medium text-red">
              {topupError}
            </p>
          )}
        </form>
      </section>

      {/* ── tasks ────────────────────────────────────────────────────── */}
      <section aria-labelledby="tasks-heading" className="space-y-3">
        <h2 id="tasks-heading" className="eyebrow">
          {tasks
            ? `${tasks.length} ${tasks.length === 1 ? "task" : "tasks"}`
            : "Tasks"}
        </h2>

        {tasks === null ? (
          <SkeletonRows label="Loading tasks…" rows={5} />
        ) : tasks.length === 0 ? (
          <div className="rounded-lg border border-dashed border-line-bright px-6 py-12 text-center">
            <EmptyTicket />
            <p className="font-medium">No tasks yet</p>
            <p className="mt-1 text-sm text-muted">
              Describe a task and HireAI will shortlist agents that can do it.
            </p>
            <Link
              href="/client/tasks/new"
              className="mt-4 inline-block text-sm font-medium text-accent hover:underline"
            >
              Submit your first task
            </Link>
          </div>
        ) : (
          <ul className="overflow-hidden rounded-xl border border-line bg-surface">
            {tasks.map((t) => (
              <li key={t.id} className="border-b border-line last:border-b-0">
                <Link
                  href={`/client/tasks/${t.id}`}
                  className="group grid grid-cols-[1fr_auto] items-center gap-x-6 gap-y-2 px-5 py-[1.125rem] transition hover:bg-accent/[0.04] sm:grid-cols-[1fr_9rem_6.5rem_11rem]"
                >
                  <div className="min-w-0">
                    <p className="line-clamp-2 font-semibold text-fg group-hover:text-accent sm:line-clamp-none sm:truncate">
                      {t.title}
                    </p>
                    <p className="mt-0.5 text-xs text-dim">
                      Submitted {new Date(t.createdAt).toLocaleDateString()}
                      {/* phones have no amount column, so the money line rides under the title */}
                      <span className="sm:hidden">
                        {", "}
                        <span className={MONEY_TONE[moneyState(t).tone]}>
                          {t.budget} cr {moneyState(t).label}
                        </span>
                      </span>
                    </p>
                  </div>
                  <div className="justify-self-end sm:justify-self-start">
                    <Badge status={t.resolution ?? t.status}>
                      {t.resolution ? statusLabel(t.resolution) : undefined}
                    </Badge>
                  </div>
                  <p className="hidden text-right sm:block">
                    <span className="tabular block text-sm font-semibold text-fg">{t.budget} cr</span>
                    <span className={`block text-xs ${MONEY_TONE[moneyState(t).tone]}`}>{moneyState(t).label}</span>
                  </p>
                  <div className="hidden sm:block">
                    <StatusTrack status={t.status} />
                  </div>
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
