"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { api, ApiError } from "@/lib/api";
import { AppShell } from "@/components/AppShell";
import { RoleGuard } from "@/components/RoleGuard";
import { Card, Badge, Button } from "@/components/ui";
import type { AdminDisputeRowDTO } from "@/lib/types";

function AdminDisputes() {
  const [rows, setRows] = useState<AdminDisputeRowDTO[]>([]);
  const [needsAttentionOnly, setNeedsAttentionOnly] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    const filter = needsAttentionOnly ? "needs_attention" : "all";
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
      <div className="flex items-end justify-between gap-4">
        <div>
          <p className="eyebrow">Backstop</p>
          <h1 className="mt-1 text-2xl font-extrabold tracking-tight">Disputes</h1>
        </div>
        <div className="flex items-center gap-2">
          <Button variant={needsAttentionOnly ? "primary" : "ghost"} onClick={() => setNeedsAttentionOnly(true)}>
            Needs attention
          </Button>
          <Button variant={needsAttentionOnly ? "ghost" : "primary"} onClick={() => setNeedsAttentionOnly(false)}>
            All
          </Button>
        </div>
      </div>

      {error && (
        <Card>
          <p role="alert" className="font-mono text-sm text-red">
            {error}
          </p>
        </Card>
      )}

      <Card className="overflow-x-auto p-0">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-line text-left font-mono text-[0.65rem] uppercase tracking-wider text-dim">
              <th className="p-3">Task</th>
              <th className="p-3">Reason</th>
              <th className="p-3">Status</th>
              <th className="p-3">Raised by</th>
              <th className="p-3">Opened</th>
              <th className="p-3" />
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.disputeId} className="border-b border-line/50 hover:bg-surface-2/50">
                <td className="p-3 text-fg">{r.taskTitle}</td>
                <td className="p-3 font-mono text-xs text-muted">{r.reasonCategory}</td>
                <td className="p-3">
                  <Badge status={r.status}>{r.status}</Badge>
                </td>
                <td className="p-3 font-mono text-xs text-muted">{r.clientName}</td>
                <td className="p-3 font-mono text-xs text-dim">{new Date(r.createdAt).toLocaleString()}</td>
                <td className="p-3 text-right">
                  <Link
                    href={`/admin/disputes/${r.disputeId}`}
                    className="font-mono text-xs font-semibold text-accent hover:underline"
                  >
                    Review →
                  </Link>
                </td>
              </tr>
            ))}
            {!loading && rows.length === 0 && (
              <tr>
                <td colSpan={6} className="p-3 font-mono text-xs text-dim">
                  Nothing here.
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
