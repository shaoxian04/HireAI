"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { api, ApiError } from "@/lib/api";
import { AppShell } from "@/components/AppShell";
import { RoleGuard } from "@/components/RoleGuard";
import { Card, Badge } from "@/components/ui";
import type { DisputeMineRowDTO, RulingCategory } from "@/lib/types";
import { PageHeader } from "@/components/PageHeader";

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
      <PageHeader
        title="Disputes"
        description="Results you rejected, and where each one stands with the arbitrator."
      />
      {error && (
        <Card>
          <p role="alert" className="text-sm text-red">
            {error}
          </p>
        </Card>
      )}
      <Card className="overflow-x-auto p-0">
        <table className="stack-table w-full text-sm">
          <thead>
            <tr className="border-b border-line bg-surface-2 text-left text-xs font-semibold text-muted">
              <th className="p-3">Task</th>
              <th className="p-3">State</th>
              <th className="p-3">Ruling</th>
              <th className="p-3">Updated</th>
              <th className="p-3" />
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.disputeId} className="border-b border-line/50 hover:bg-surface-2/50">
                <td className="p-3 text-fg">{r.taskTitle}</td>
                <td data-label="State" className="p-3">
                  <Badge status={r.status}>{LABEL[r.status] ?? r.status}</Badge>
                </td>
                <td data-label="Ruling" className="p-3 text-sm">
                  <Ruling status={r.status} category={r.proposedCategory} />
                </td>
                <td data-label="Updated" className="p-3 text-xs text-dim">{new Date(r.updatedAt).toLocaleString()}</td>
                <td className="p-3 text-right">
                  <Link
                    href={`/client/tasks/${r.taskId}`}
                    className="font-mono text-xs font-semibold text-accent hover:underline"
                  >
                    Open
                  </Link>
                </td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr>
                <td colSpan={5} className="px-6 py-14 text-center">
                  <p className="font-semibold text-fg">No disputes</p>
                  <p className="mx-auto mt-1 max-w-md text-sm text-muted">
                    A dispute opens when you reject a result. The arbitrator then judges it against the
                    task&apos;s output contract, and the outcome shows up here.
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

/** What the ruling means for the client's money, said plainly. */
const RULING_TEXT: Record<RulingCategory, string> = {
  FULFILLED: "Builder paid",
  PARTIALLY_FULFILLED: "Split settlement",
  NOT_FULFILLED: "Full refund to you",
};

function Ruling({ status, category }: { status: string; category: RulingCategory | null }) {
  if (!category) return <span className="text-dim">Not ruled yet</span>;
  if (status === "RESOLVED") return <span className="font-medium text-fg">{RULING_TEXT[category]}</span>;
  // RULED: the arbitrator has proposed this, and it waits on the client
  return (
    <span className="text-fg">
      {RULING_TEXT[category]} <span className="text-xs text-amber">proposed, open the task to respond</span>
    </span>
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
