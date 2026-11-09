"use client";

import { useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { AppShell } from "@/components/AppShell";
import { RoleGuard } from "@/components/RoleGuard";
import { Card, Badge, SkeletonRows, ButtonLink } from "@/components/ui";
import { PageHeader } from "@/components/PageHeader";

/** How many recent tasks the overview shows before "Show all". */
const TASK_PREVIEW = 10;
import type {
  AdminOverviewDTO,
  AdminTaskRowDTO,
  AdminUserRowDTO,
  AdminAgentRowDTO,
} from "@/lib/types";

function Tile({
  label,
  value,
  tone = "text-fg",
  className = "",
}: {
  label: string;
  value: string | number;
  tone?: string;
  className?: string;
}) {
  return (
    <div className={`bg-surface px-5 py-4 ${className}`}>
      <p className="text-xs text-dim">{label}</p>
      <p className={`display tabular mt-1 text-[2.4rem] ${tone}`}>{value}</p>
    </div>
  );
}

function AdminOverview() {
  const [overview, setOverview] = useState<AdminOverviewDTO | null>(null);
  const [tasks, setTasks] = useState<AdminTaskRowDTO[]>([]);
  const [users, setUsers] = useState<AdminUserRowDTO[]>([]);
  const [agents, setAgents] = useState<AdminAgentRowDTO[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [showAllTasks, setShowAllTasks] = useState(false);

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
        <p role="alert" className="text-sm text-red">
          {error}
        </p>
      </Card>
    );
  }
  if (!overview) {
    return (
      <Card>
        <SkeletonRows label="Loading overview…" rows={3} />
      </Card>
    );
  }

  return (
    <div className="space-y-8">
      <PageHeader
        title="Admin overview"
        description="Disputes that need a decision, and where the platform's money and work stand."
        actions={
          <ButtonLink href="/admin/disputes">Review disputes</ButtonLink>
        }
      />

      <section className="space-y-3">
        <h2 className="eyebrow">Disputes</h2>
        <div className="grid grid-cols-2 gap-px overflow-hidden rounded-xl border border-line bg-line lg:grid-cols-4">
          <Tile
            label="Need attention"
            value={overview.disputesOpen + overview.disputesEscalated}
            tone={overview.disputesOpen + overview.disputesEscalated > 0 ? "text-amber" : "text-fg"}
          />
          <Tile label="Escalated" value={overview.disputesEscalated} tone={overview.disputesEscalated > 0 ? "text-red" : "text-fg"} />
          <Tile label="Arbitrating" value={overview.disputesArbitrating} />
          <Tile label="Resolved" value={overview.disputesResolved} />
        </div>
      </section>

      <section className="space-y-3">
        <h2 className="eyebrow">Platform</h2>
        <div className="grid grid-cols-2 gap-px overflow-hidden rounded-xl border border-line bg-line sm:grid-cols-3 lg:grid-cols-5">
          <Tile label="Tasks" value={overview.tasksTotal} />
          <Tile label="Users" value={overview.usersTotal} />
          <Tile label="Agents" value={overview.agentsTotal} />
          <Tile label="Escrow held" value={`${overview.escrowHeld} cr`} tone="text-accent" />
          {/* spans the leftover cells on 2- and 3-column grids, so no row ends in an empty box */}
          <Tile
            label="Commission earned"
            value={`${overview.commissionEarned} cr`}
            className="col-span-2 lg:col-span-1"
          />
        </div>
      </section>

      <section className="space-y-3">
        <div className="flex items-baseline justify-between gap-3">
          <h2 className="eyebrow">Recent tasks</h2>
          {tasks.length > TASK_PREVIEW && (
            <button
              type="button"
              onClick={() => setShowAllTasks((v) => !v)}
              className="text-sm font-semibold text-accent hover:underline"
            >
              {showAllTasks ? "Show fewer" : `Show all ${tasks.length}`}
            </button>
          )}
        </div>
        <Card className="overflow-hidden rounded-xl p-0 sm:overflow-x-auto">
          <table className="stack-table w-full text-sm sm:min-w-[34rem]">
            <thead>
              <tr className="border-b border-line bg-surface-2 text-left text-xs font-semibold text-muted">
                <th className="p-3">Title</th>
                <th className="p-3">Client</th>
                <th className="p-3">Status</th>
                <th className="p-3">Budget</th>
              </tr>
            </thead>
            <tbody>
              {(showAllTasks ? tasks : tasks.slice(0, TASK_PREVIEW)).map((t) => (
                <tr key={t.id} className="border-b border-line/50">
                  <td className="p-3 text-fg">{t.title}</td>
                  <td data-label="Client" className="p-3 text-xs text-muted">{t.clientName}</td>
                  <td data-label="Status" className="p-3">
                    <Badge status={t.status} />
                  </td>
                  <td data-label="Budget" className="p-3 tabular font-semibold text-fg">{t.budget} cr</td>
                </tr>
              ))}
              {tasks.length === 0 && (
                <tr>
                  <td colSpan={4} className="p-3 text-xs text-dim">
                    No tasks yet.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </Card>
      </section>

      <section className="grid gap-8">
        <div className="space-y-3">
          <h2 className="eyebrow">Users &amp; wallets</h2>
          <Card className="overflow-hidden rounded-xl p-0 sm:overflow-x-auto">
            <table className="stack-table w-full text-sm sm:min-w-[34rem]">
              <thead>
                <tr className="border-b border-line bg-surface-2 text-left text-xs font-semibold text-muted">
                  <th className="p-3">User</th>
                  <th className="p-3">Roles</th>
                  <th className="p-3">Available</th>
                  <th className="p-3">Escrow</th>
                </tr>
              </thead>
              <tbody>
                {users.map((u) => (
                  <tr key={u.id} className="border-b border-line/50">
                    <td className="p-3 text-xs text-fg">{u.email}</td>
                    <td data-label="Roles" className="p-3 text-xs text-muted">{u.roles.join(", ")}</td>
                    <td data-label="Available" className="p-3 tabular text-muted">{u.availableBalance} cr</td>
                    <td data-label="Escrow" className="p-3 tabular text-muted">{u.escrowBalance} cr</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
        </div>
        <div className="space-y-3">
          <h2 className="eyebrow">Agents</h2>
          <Card className="overflow-hidden rounded-xl p-0 sm:overflow-x-auto">
            <table className="stack-table w-full text-sm sm:min-w-[34rem]">
              <thead>
                <tr className="border-b border-line bg-surface-2 text-left text-xs font-semibold text-muted">
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
                    <td data-label="Builder" className="p-3 text-xs text-muted">{a.builderName}</td>
                    <td data-label="Status" className="p-3">
                      <Badge status={a.status} />
                    </td>
                    <td data-label="Price" className="p-3 tabular text-muted">{a.price == null ? "—" : `${a.price} cr`}</td>
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
