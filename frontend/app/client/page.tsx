"use client";

import { useEffect, useState, type FormEvent } from "react";
import Link from "next/link";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import type { TaskDTO, TopupRequest, WalletDTO } from "@/lib/types";
import { Badge, Button, Card, Input } from "@/components/ui";

function ClientDashboard() {
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

  return (
    <div className="space-y-8">
      <header>
        <h1 className="text-2xl font-semibold tracking-tight text-slate-900">My wallet</h1>
        <p className="mt-1 text-sm text-slate-500">
          Credits fund tasks; submitting one freezes its budget in escrow.
        </p>
      </header>

      {error && (
        <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </p>
      )}

      <Card>
        {wallet === null ? (
          <p className="text-sm text-slate-500">Loading wallet…</p>
        ) : (
          <div className="flex flex-wrap items-end justify-between gap-6">
            <div className="flex gap-10">
              <div>
                <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
                  Available
                </p>
                <p className="mt-1 text-3xl font-semibold text-slate-900">
                  {wallet.availableBalance}
                </p>
              </div>
              <div>
                <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
                  In escrow
                </p>
                <p className="mt-1 text-3xl font-semibold text-amber-600">{wallet.escrowBalance}</p>
              </div>
            </div>
            <form onSubmit={topup} className="flex items-center gap-2">
              <Input
                type="number"
                min={1}
                value={topupAmount}
                aria-label="Top-up amount"
                onChange={(e) => setTopupAmount(Number(e.target.value))}
                className="w-28"
              />
              <Button type="submit" disabled={toppingUp}>
                {toppingUp ? "Topping up…" : "Top up"}
              </Button>
            </form>
          </div>
        )}
      </Card>

      <section className="space-y-4">
        <header className="flex items-end justify-between">
          <h2 className="text-lg font-semibold text-slate-900">My tasks</h2>
          <Link
            href="/client/tasks/new"
            className="inline-flex items-center justify-center rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white transition hover:bg-slate-800"
          >
            Submit task
          </Link>
        </header>
        {tasks === null ? (
          <p className="text-sm text-slate-500">Loading tasks…</p>
        ) : tasks.length === 0 ? (
          <Card>
            <p className="text-sm text-slate-500">No tasks yet.</p>
          </Card>
        ) : (
          <ul className="space-y-2">
            {tasks.map((t) => (
              <li key={t.id}>
                <Link
                  href={`/client/tasks/${t.id}`}
                  className="flex items-center justify-between rounded-lg border border-slate-200 bg-white px-4 py-3 shadow-sm transition hover:border-slate-300 hover:bg-slate-50"
                >
                  <span className="font-medium text-slate-900">{t.title}</span>
                  <Badge status={t.status}>{t.status}</Badge>
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
    <RoleGuard role="CLIENT">
      <ClientDashboard />
    </RoleGuard>
  );
}
