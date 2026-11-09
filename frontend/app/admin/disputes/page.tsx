"use client";

import { REJECT_LABEL } from "@/components/DisputeProgressPanel";
import type { RejectReason } from "@/lib/types";
import { useEffect, useState } from "react";
import Link from "next/link";
import { api, ApiError } from "@/lib/api";
import { AppShell } from "@/components/AppShell";
import { RoleGuard } from "@/components/RoleGuard";
import { Card, Badge, SkeletonRows } from "@/components/ui";
import type { AdminDisputeRowDTO } from "@/lib/types";
import { PageHeader } from "@/components/PageHeader";

function AdminDisputes() {
  const [rows, setRows] = useState<AdminDisputeRowDTO[]>([]);
  const [needsAttentionOnly, setNeedsAttentionOnly] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    const filter = needsAttentionOnly ? "needs_attention" : "all";
    // eslint-disable-next-line react-hooks/set-state-in-effect -- show the loading state again when the filter changes
    setLoading(true);
    api<AdminDisputeRowDTO[]>(`/admin/disputes?filter=${filter}`)
      .then((r) => {
        if (!cancelled) {
          setRows(r);
          setError(null);
        }
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof ApiError ? e.message : "Failed to load disputes");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [needsAttentionOnly]);

  return (
    <div className="space-y-6">
      <PageHeader
        title="Disputes"
        description="Rejected results the arbitrator escalated, or that are still being decided. Open one to rule on it."
        actions={
          <div className="flex rounded-md border border-line-bright bg-surface p-0.5" role="group" aria-label="Filter">
            <button
              type="button"
              aria-pressed={needsAttentionOnly}
              onClick={() => setNeedsAttentionOnly(true)}
              className={`rounded px-3 py-1.5 text-sm font-medium transition ${needsAttentionOnly ? "bg-accent text-ink" : "text-muted hover:text-fg"}`}
            >
              Needs attention
            </button>
            <button
              type="button"
              aria-pressed={!needsAttentionOnly}
              onClick={() => setNeedsAttentionOnly(false)}
              className={`rounded px-3 py-1.5 text-sm font-medium transition ${!needsAttentionOnly ? "bg-accent text-ink" : "text-muted hover:text-fg"}`}
            >
              All
            </button>
          </div>
        }
      />

      {error && (
        <Card>
          <p role="alert" className="text-sm text-red">
            {error}
          </p>
        </Card>
      )}

      <Card className="overflow-hidden p-0 sm:overflow-x-auto">
        <table className="stack-table w-full text-sm" aria-busy={loading}>
          <thead>
            <tr className="border-b border-line bg-surface-2 text-left text-xs font-semibold text-muted">
              <th className="p-3">Task</th>
              <th className="p-3">Reason</th>
              <th className="p-3">Status</th>
              <th className="p-3">Raised by</th>
              <th className="p-3">Opened</th>
              <th className="p-3" />
            </tr>
          </thead>
          <tbody>
            {!loading && rows.map((r) => (
              <tr key={r.disputeId} className="border-b border-line/50 hover:bg-surface-2/50">
                <td className="p-3 text-fg">{r.taskTitle}</td>
                <td data-label="Reason" className="p-3 text-xs text-muted">{REJECT_LABEL[r.reasonCategory as RejectReason] ?? r.reasonCategory}</td>
                <td data-label="Status" className="p-3">
                  <Badge status={r.status} />
                </td>
                <td data-label="Raised by" className="p-3 text-xs text-muted">{r.clientName}</td>
                <td data-label="Opened" className="p-3 text-xs text-dim">{new Date(r.createdAt).toLocaleString()}</td>
                <td className="p-3 text-right">
                  <Link
                    href={`/admin/disputes/${r.disputeId}`}
                    className="text-sm font-semibold text-accent hover:underline"
                  >
                    Review<span className="sr-only">: {r.taskTitle}</span>
                  </Link>
                </td>
              </tr>
            ))}
            {loading && (
              <tr>
                <td colSpan={6} className="p-0">
                  <div className="p-4">
                    <SkeletonRows label="Loading disputes…" rows={4} />
                  </div>
                </td>
              </tr>
            )}
            {!loading && rows.length === 0 && (
              <tr>
                <td colSpan={6} className="px-6 py-14 text-center">
                  <p className="font-semibold text-fg">No disputes in this view</p>
                  <p className="mt-1 text-sm text-muted">
                    Disputes appear here when a client rejects a result and the arbitrator escalates it.
                  </p>
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </Card>
    </div>
  );
}

export default function Page() {
  return (
    <AppShell>
      <RoleGuard role="ADMIN">
        <AdminDisputes />
      </RoleGuard>
    </AppShell>
  );
}
