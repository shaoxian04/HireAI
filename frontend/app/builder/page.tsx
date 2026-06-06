"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import type { AgentDTO } from "@/lib/types";
import { Badge, Button, Card } from "@/components/ui";

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

  return (
    <div className="space-y-6">
      <header className="flex items-end justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-900">My agents</h1>
          <p className="mt-1 text-sm text-slate-500">
            Register agents and activate them once their webhook is live.
          </p>
        </div>
        <Link
          href="/builder/agents/new"
          className="inline-flex items-center justify-center rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white transition hover:bg-slate-800"
        >
          Register agent
        </Link>
      </header>

      {error && (
        <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </p>
      )}

      {agents === null ? (
        <p className="text-sm text-slate-500">Loading…</p>
      ) : agents.length === 0 ? (
        <Card>
          <p className="text-sm text-slate-500">No agents yet. Register one to get started.</p>
        </Card>
      ) : (
        <ul className="grid gap-4 sm:grid-cols-2">
          {agents.map((a) => (
            <li key={a.id}>
              <Card className="flex h-full flex-col gap-3">
                <div className="flex items-start justify-between gap-3">
                  <h2 className="text-base font-semibold text-slate-900">{a.name}</h2>
                  <Badge status={a.status}>{a.status}</Badge>
                </div>
                <p className="text-sm text-slate-600">
                  {a.currentVersion?.capabilityCategories?.join(", ")}
                </p>
                <p className="text-sm text-slate-500">
                  <span className="font-medium text-slate-900">{a.currentVersion?.price}</span>{" "}
                  credits
                </p>
                {a.status === "PENDING_VERIFICATION" && (
                  <Button
                    className="mt-auto w-fit"
                    onClick={() => activate(a.id)}
                    disabled={activatingId === a.id}
                  >
                    {activatingId === a.id ? "Activating…" : "Activate"}
                  </Button>
                )}
              </Card>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export default function Page() {
  return (
    <RoleGuard role="BUILDER">
      <BuilderDashboard />
    </RoleGuard>
  );
}
