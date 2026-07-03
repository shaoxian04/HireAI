"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { api, ApiError } from "@/lib/api";
import { AppShell } from "@/components/AppShell";
import { RoleGuard } from "@/components/RoleGuard";
import { Card, Badge } from "@/components/ui";
import type { DisputeMineRowDTO } from "@/lib/types";

const LABEL: Record<string, string> = {
  RULED: "Awaiting your decision",
  ARBITRATING: "Under review",
  OPEN: "Under review",
  ESCALATED: "Under admin review",
  RESOLVED: "Resolved",
};

function ClientDisputes() {
  const [rows, setRows] = useState<DisputeMineRowDTO[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api<DisputeMineRowDTO[]>("/disputes/mine")
      .then((r) => {
        if (!cancelled) setRows(r);
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof ApiError ? e.message : "Failed to load disputes");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <div className="space-y-6">
      <div>
        <p className="eyebrow">Your disputes</p>
        <h1 className="mt-1 text-2xl font-extrabold tracking-tight">Disputes</h1>
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
              <th className="p-3">State</th>
              <th className="p-3">Updated</th>
              <th className="p-3" />
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.disputeId} className="border-b border-line/50 hover:bg-surface-2/50">
                <td className="p-3 text-fg">{r.taskTitle}</td>
                <td className="p-3">
                  <Badge status={r.status}>{LABEL[r.status] ?? r.status}</Badge>
                </td>
                <td className="p-3 font-mono text-xs text-dim">{new Date(r.updatedAt).toLocaleString()}</td>
                <td className="p-3 text-right">
                  <Link
                    href={`/client/tasks/${r.taskId}`}
                    className="font-mono text-xs font-semibold text-accent hover:underline"
                  >
                    Open →
                  </Link>
                </td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr>
                <td colSpan={4} className="p-3 font-mono text-xs text-dim">
                  No disputes.
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
      <RoleGuard role="CLIENT">
        <ClientDisputes />
      </RoleGuard>
    </AppShell>
  );
}
