"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { api, ApiError } from "@/lib/api";
import { AppShell } from "@/components/AppShell";
import { RoleGuard } from "@/components/RoleGuard";
import { Card, Badge } from "@/components/ui";
import type {
  AdminOverviewDTO,
  AdminTaskRowDTO,
  AdminUserRowDTO,
  AdminAgentRowDTO,
} from "@/lib/types";

function Tile({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="rounded-md border border-line bg-surface-2 p-4">
      <p className="font-mono text-[0.65rem] uppercase tracking-[0.18em] text-dim">{label}</p>
      <p className="mt-1 text-2xl font-extrabold tabular tracking-tight text-fg">{value}</p>
    </div>
  );
}

function AdminOverview() {
  const [overview, setOverview] = useState<AdminOverviewDTO | null>(null);
  const [tasks, setTasks] = useState<AdminTaskRowDTO[]>([]);
  const [users, setUsers] = useState<AdminUserRowDTO[]>([]);
  const [agents, setAgents] = useState<AdminAgentRowDTO[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      api<AdminOverviewDTO>("/admin/overview"),
      api<AdminTaskRowDTO[]>("/admin/tasks"),
      api<AdminUserRowDTO[]>("/admin/users"),
      api<AdminAgentRowDTO[]>("/admin/agents"),
    ])
      .then(([o, t, u, a]) => {
        if (cancelled) return;
        setOverview(o);
        setTasks(t);
        setUsers(u);
        setAgents(a);
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof ApiError ? e.message : "Failed to load admin overview");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (error) {
    return (
      <Card>
        <p role="alert" className="font-mono text-sm text-red">
          {error}
        </p>
      </Card>
    );
  }
  if (!overview) {
    return (
      <Card>
        <p className="font-mono text-sm text-dim">Loading overview…</p>
      </Card>
    );
  }

  return (
    <div className="space-y-8">
      <div>
        <p className="eyebrow">Platform</p>
        <h1 className="mt-1 text-2xl font-extrabold tracking-tight">Admin overview</h1>
      </div>

      <section className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
        <Link href="/admin/disputes" className="contents">
          <Tile label="Disputes · needs attention" value={overview.disputesOpen + overview.disputesEscalated} />
        </Link>
        <Tile label="Escalated" value={overview.disputesEscalated} />
        <Tile label="Arbitrating" value={overview.disputesArbitrating} />
        <Tile label="Resolved disputes" value={overview.disputesResolved} />
        <Tile label="Tasks" value={overview.tasksTotal} />
        <Tile label="Users" value={overview.usersTotal} />
        <Tile label="Agents" value={overview.agentsTotal} />
        <Tile label="Escrow held" value={`${overview.escrowHeld} cr`} />
        <Tile label="Commission earned" value={`${overview.commissionEarned} cr`} />
      </section>

      <section className="space-y-3">
        <h2 className="eyebrow">Recent tasks</h2>
        <Card className="overflow-x-auto p-0">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line text-left font-mono text-[0.65rem] uppercase tracking-wider text-dim">
                <th className="p-3">Title</th>
                <th className="p-3">Client</th>
                <th className="p-3">Status</th>
                <th className="p-3">Budget</th>
              </tr>
            </thead>
            <tbody>
              {tasks.map((t) => (
                <tr key={t.id} className="border-b border-line/50">
                  <td className="p-3 text-fg">{t.title}</td>
                  <td className="p-3 font-mono text-xs text-muted">{t.clientName}</td>
                  <td className="p-3">
                    <Badge status={t.status}>{t.status}</Badge>
                  </td>
                  <td className="p-3 tabular text-accent">{t.budget} cr</td>
                </tr>
              ))}
              {tasks.length === 0 && (
                <tr>
                  <td colSpan={4} className="p-3 font-mono text-xs text-dim">
                    No tasks yet.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </Card>
      </section>

      <section className="grid gap-6 lg:grid-cols-2">
        <div className="space-y-3">
          <h2 className="eyebrow">Users &amp; wallets</h2>
          <Card className="overflow-x-auto p-0">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line text-left font-mono text-[0.65rem] uppercase tracking-wider text-dim">
                  <th className="p-3">User</th>
                  <th className="p-3">Roles</th>
                  <th className="p-3">Available</th>
                  <th className="p-3">Escrow</th>
                </tr>
              </thead>
              <tbody>
                {users.map((u) => (
                  <tr key={u.id} className="border-b border-line/50">
                    <td className="p-3 font-mono text-xs text-fg">{u.email}</td>
                    <td className="p-3 font-mono text-[0.65rem] text-muted">{u.roles.join(", ")}</td>
                    <td className="p-3 tabular text-muted">{u.availableBalance} cr</td>
                    <td className="p-3 tabular text-muted">{u.escrowBalance} cr</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
        </div>
        <div className="space-y-3">
          <h2 className="eyebrow">Agents</h2>
          <Card className="overflow-x-auto p-0">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line text-left font-mono text-[0.65rem] uppercase tracking-wider text-dim">
                  <th className="p-3">Name</th>
                  <th className="p-3">Builder</th>
                  <th className="p-3">Status</th>
                  <th className="p-3">Price</th>
                </tr>
              </thead>
              <tbody>
                {agents.map((a) => (
                  <tr key={a.id} className="border-b border-line/50">
                    <td className="p-3 text-fg">{a.name}</td>
                    <td className="p-3 font-mono text-xs text-muted">{a.builderName}</td>
                    <td className="p-3">
                      <Badge status={a.status}>{a.status}</Badge>
                    </td>
                    <td className="p-3 tabular text-muted">{a.price == null ? "—" : `${a.price} cr`}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
        </div>
      </section>
    </div>
  );
}

export default function Page() {
  return (
    <AppShell>
      <RoleGuard role="ADMIN">
        <AdminOverview />
      </RoleGuard>
    </AppShell>
  );
}
