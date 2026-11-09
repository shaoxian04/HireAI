"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import type { AgentDTO, WalletDTO } from "@/lib/types";
import { Badge, Button, ButtonLink, Input, Select, Skeleton, SkeletonRows, statusLabel } from "@/components/ui";
import { PageHeader } from "@/components/PageHeader";
import { EmptyTicket } from "@/components/EmptyTicket";

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
  const [wallet, setWallet] = useState<WalletDTO | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [activatingId, setActivatingId] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState<"ALL" | AgentDTO["status"]>("ALL");

  useEffect(() => {
    api<AgentDTO[]>("/agents")
      .then(setAgents)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Failed to load agents"));
    // The builder wallet is opened on the first payout — until then there is nothing to
    // show, so a missing wallet is not an error: just leave the earnings tile at 0.
    api<WalletDTO>("/wallet")
      .then(setWallet)
      .catch(() => setWallet(null));
  }, []);

  async function activate(id: string) {
    setActivatingId(id);
    setError(null);
    setNotice(null);
    try {
      const updated = await api<AgentDTO>(`/agents/${id}/activate`, { method: "POST" });
      setAgents((prev) => prev?.map((a) => (a.id === id ? updated : a)) ?? null);
      setNotice(`${updated.name} is active and can now be matched to tasks.`);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Activation failed");
    } finally {
      setActivatingId(null);
    }
  }

  const q = query.trim().toLowerCase();
  const visible = (agents ?? []).filter(
    (a) =>
      (statusFilter === "ALL" || a.status === statusFilter) &&
      (!q ||
        a.name.toLowerCase().includes(q) ||
        (a.currentVersion?.capabilityCategories ?? []).some((c) => c.toLowerCase().includes(q))),
  );

  const stats = {
    total: agents?.length ?? 0,
    active: agents?.filter((a) => a.status === "ACTIVE").length ?? 0,
    pending: agents?.filter((a) => a.status === "PENDING_VERIFICATION").length ?? 0,
  };

  return (
    <div className="space-y-8">
      <PageHeader
        title="My agents"
        description="Register an agent, then activate it once its webhook is live. Only active agents listed on the marketplace receive work."
        actions={
          <ButtonLink href="/builder/agents/new">Register agent</ButtonLink>
        }
      />

      {error && (
        <p role="alert" className="rounded-md border border-red/30 bg-red/10 px-3 py-2 text-sm text-red">
          {error}
        </p>
      )}

      {/* ── summary ──────────────────────────────────────────────────── */}
      <dl
        aria-busy={agents === null}
        className="grid grid-cols-2 divide-line rounded-xl border border-line bg-surface sm:grid-cols-4 sm:divide-x"
      >
        <div className="px-5 py-4">
          <dt className="text-xs text-dim">Registered</dt>
          <dd className="display tabular mt-1 text-[2.4rem]">
            <Figure value={agents && stats.total} />
          </dd>
        </div>
        <div className="px-5 py-4">
          <dt className="text-xs text-dim">Active</dt>
          <dd className="display tabular mt-1 text-[2.4rem]">
            <Figure value={agents && stats.active} />
          </dd>
        </div>
        <div className="px-5 py-4">
          <dt className="text-xs text-dim">Awaiting activation</dt>
          <dd className={`display tabular mt-1 text-[2.4rem] ${stats.pending > 0 ? "text-amber" : ""}`}>
            <Figure value={agents && stats.pending} />
          </dd>
        </div>
        <div className="group relative px-5 py-4 transition hover:bg-accent/[0.04]">
          <dt className="text-xs text-dim group-hover:text-accent">
            {/* the whole tile is the link target; the link text is the label */}
            <Link href="/builder/earnings" className="after:absolute after:inset-0">
              Wallet balance (cr)
              <span className="sr-only">, view earnings</span>
            </Link>
          </dt>
          <dd className="display tabular mt-1 text-[2.4rem]">
            <Figure value={wallet && wallet.availableBalance.toFixed(2)} />
          </dd>
        </div>
      </dl>

      {/* ── agents ───────────────────────────────────────────────────── */}
      {agents === null ? (
        <SkeletonRows label="Loading agents…" rows={5} />
      ) : agents.length === 0 ? (
        <div className="rounded-lg border border-dashed border-line-bright px-6 py-12 text-center">
          <EmptyTicket />
          <p className="font-medium">No agents yet</p>
          <p className="mt-1 text-sm text-muted">Register one to start receiving work.</p>
          <Link href="/builder/agents/new" className="mt-4 inline-block text-sm font-medium text-accent hover:underline">
            Register your first agent
          </Link>
        </div>
      ) : (
        <div className="space-y-3">
        {notice && (
          <p role="status" className="flex items-center gap-2 rounded-lg border border-green/30 bg-green/10 px-4 py-2.5 text-sm text-fg">
            <span aria-hidden className="size-2 rounded-full bg-green" />
            {notice}
          </p>
        )}
        <UnlistedWarning agents={agents} />
        <div className="flex flex-wrap items-end gap-3">
          <div className="min-w-[12rem] flex-1 space-y-1.5">
            <label htmlFor="agent-search" className="block text-xs font-semibold text-muted">
              Find an agent
            </label>
            <Input
              id="agent-search"
              type="search"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Name or category"
            />
          </div>
          <div className="w-44 space-y-1.5">
            <label htmlFor="agent-status" className="block text-xs font-semibold text-muted">
              Status
            </label>
            <Select
              id="agent-status"
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value as typeof statusFilter)}
            >
              <option value="ALL">All ({agents.length})</option>
              {(["ACTIVE", "PENDING_VERIFICATION", "SUSPENDED", "DEACTIVATED"] as const).map((st) => (
                <option key={st} value={st}>
                  {statusLabel(st)} ({agents.filter((a) => a.status === st).length})
                </option>
              ))}
            </Select>
          </div>
        </div>
        {visible.length === 0 ? (
          <div className="rounded-lg border border-dashed border-line-bright px-6 py-10 text-center">
            <p className="font-medium">No agents match</p>
            <p className="mt-1 text-sm text-muted">Clear the search or pick another status.</p>
          </div>
        ) : (
        <div className="overflow-hidden rounded-xl border border-line bg-surface sm:overflow-x-auto">
          <table className="stack-table w-full text-sm sm:min-w-[56rem]">
            <thead>
              <tr className="border-b border-line bg-surface-2 text-left text-xs font-semibold text-muted">
                <th scope="col" className="px-4 py-2.5 font-medium">Agent</th>
                <th scope="col" className="px-4 py-2.5 font-medium">Status</th>
                <th scope="col" className="px-4 py-2.5 text-right font-medium">Price</th>
                <th scope="col" className="px-4 py-2.5 text-right font-medium">
                  Routing score
                  <span className="sr-only"> (0 to 100; routing ranks agents by it)</span>
                </th>
                <th scope="col" className="px-4 py-2.5 text-right font-medium">Time limit</th>
                <th scope="col" className="px-4 py-2.5 font-medium">Webhook</th>
                <th scope="col" className="px-4 py-2.5"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {visible.map((a) => (
                <tr key={a.id} className="transition hover:bg-surface">
                  <td className="max-w-72 px-4 py-3">
                    <Link href={`/builder/agents/${a.id}`} className="block truncate font-medium text-fg hover:text-accent">
                      {a.name}
                    </Link>
                    {!a.listed && (
                      <p className="mt-0.5 text-xs font-medium text-amber">Not listed · receives no work</p>
                    )}
                    <p className="mt-0.5 truncate text-xs text-dim">
                      <span className="font-mono">#{a.id.slice(0, 8)}</span>
                      {a.currentVersion?.capabilityCategories?.length ? (
                        <> · {a.currentVersion.capabilityCategories.join(", ")}</>
                      ) : null}
                    </p>
                  </td>
                  <td data-label="Status" className="px-4 py-3">
                    <Badge status={a.status} />
                  </td>
                  <td data-label="Price" className="tabular px-4 py-3 text-right font-semibold">{a.currentVersion?.price} cr</td>
                  <td data-label="Routing score" className="tabular px-4 py-3 text-right text-muted">
                    {/* 1dp, matching the Reputation tab — the stored 2dp is false precision on a
                        shrunk score, and "50" beside "56.52" read as two different scales. */}
                    {a.reputationScore.toFixed(1)}
                  </td>
                  <td data-label="Time limit" className="tabular px-4 py-3 text-right text-muted">
                    {a.currentVersion?.maxExecutionSeconds}s
                  </td>
                  <td data-label="Webhook" className="max-w-56 truncate px-4 py-3 font-mono text-xs text-dim">
                    {hostOf(a.currentVersion?.webhookUrl ?? "")}
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex justify-end gap-2">
                      {a.status === "PENDING_VERIFICATION" && (
                        <Button size="sm" onClick={() => activate(a.id)} disabled={activatingId === a.id}>
                          {activatingId === a.id ? "Activating…" : "Activate"}
                        </Button>
                      )}
                      <ButtonLink href={`/builder/agents/${a.id}`} size="sm" variant="secondary">Manage</ButtonLink>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        )}
        <p className="text-xs text-dim">
          Routing score is the number routing ranks your agents by, from 0 to 100. A new agent starts at
          50. Open an agent&apos;s Reputation tab to see how it is built and whether it is proven yet.
        </p>
        </div>
      )}
    </div>
  );
}

/**
 * Matching (the client shortlist, routed tasks and failover) only considers agents whose storefront
 * is listed, so an unlisted agent gets no work at all. Says so, and links to each one's storefront.
 */
function UnlistedWarning({ agents }: { agents: AgentDTO[] }) {
  const unlisted = agents.filter((a) => !a.listed);
  if (unlisted.length === 0) return null;
  const one = unlisted.length === 1;
  return (
    <aside
      role="note"
      aria-label="Unlisted agents"
      className="rounded-md border border-line border-l-4 border-l-amber bg-surface-2 px-4 py-3 text-sm text-fg"
    >
      <p>
        <span className="font-semibold">
          {unlisted.length} of your agents {one ? "is" : "are"} not listed on the marketplace,
        </span>{" "}
        so {one ? "it receives" : "they receive"} no work: client shortlists and routed tasks only
        consider listed agents. Tick &ldquo;Listed on marketplace&rdquo; on the agent&apos;s Storefront tab.
      </p>
      <ul className="mt-2 flex flex-wrap gap-x-4 gap-y-1">
        {unlisted.map((a) => (
          <li key={a.id}>
            <Link href={`/builder/agents/${a.id}`} className="text-xs font-medium text-accent hover:underline">
              List {a.name}
            </Link>
          </li>
        ))}
      </ul>
    </aside>
  );
}

/** A summary figure, or a placeholder bar while its data loads (never a misleading 0). */
function Figure({ value }: { value: number | string | null }) {
  if (value === null) {
    return (
      <>
        <span className="sr-only">Loading</span>
        <Skeleton className="mt-2 h-8 w-14" />
      </>
    );
  }
  return <>{value}</>;
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
