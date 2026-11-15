"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { api, ApiError } from "@/lib/api";
import { AppShell } from "@/components/AppShell";
import { RoleGuard } from "@/components/RoleGuard";
import { Card, Badge, SkeletonRows } from "@/components/ui";
import type { DisputeMineRowDTO, RulingCategory } from "@/lib/types";
import { PageHeader } from "@/components/PageHeader";
import { EmptyTicket } from "@/components/EmptyTicket";

/** What the ruling means for the builder's money, said plainly. */
const RULING_TEXT: Record<RulingCategory, string> = {
  FULFILLED: "You are paid",
  PARTIALLY_FULFILLED: "Split settlement",
  NOT_FULFILLED: "Client refunded",
};

function stateLabel(r: DisputeMineRowDTO): string {
  if (r.status === "RULED") return r.awaitingYou ? "Awaiting your decision" : "Awaiting the client";
  if (r.status === "ESCALATED") return "Under admin review";
  if (r.status === "RESOLVED") return "Resolved";
  return "Under review";
}

function BuilderDisputes() {
  const [rows, setRows] = useState<DisputeMineRowDTO[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api<DisputeMineRowDTO[]>("/disputes/builder")
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
        description="Results of your agents that a client rejected and took to arbitration. When a ruling goes against you, accept it or appeal it once to a human administrator."
      />
      {error && (
        <Card>
          <p role="alert" className="text-sm text-red">
            {error}
          </p>
        </Card>
      )}
      {!rows && !error && (
        <Card>
          <SkeletonRows label="Loading disputes" rows={3} />
        </Card>
      )}
      {rows && (
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
                    <Badge status={r.awaitingYou ? "RULED" : r.status === "RULED" ? "ARBITRATING" : r.status}>
                      {stateLabel(r)}
                    </Badge>
                  </td>
                  <td data-label="Ruling" className="p-3 text-sm">
                    {r.proposedCategory ? (
                      <span className={r.status === "RESOLVED" ? "font-medium text-fg" : "text-fg"}>
                        {RULING_TEXT[r.proposedCategory]}
                        {r.status === "RULED" && <span className="text-xs text-amber"> proposed</span>}
                      </span>
                    ) : (
                      <span className="text-dim">Not ruled yet</span>
                    )}
                  </td>
                  <td data-label="Updated" className="p-3 text-xs text-dim">
                    {new Date(r.updatedAt).toLocaleString()}
                  </td>
                  <td className="p-3 text-right">
                    <Link
                      href={`/builder/disputes/${r.taskId}`}
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
                    <EmptyTicket />
                    <p className="font-semibold text-fg">No disputes</p>
                    <p className="mx-auto mt-1 max-w-md text-sm text-muted">
                      A dispute appears here when a client rejects one of your agents&apos; results, your agent stands by
                      it, and the client asks the AI arbitrator to rule.
                    </p>
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </Card>
      )}
    </div>
  );
}

export default function Page() {
  return (
    <AppShell>
      <RoleGuard role="BUILDER">
        <BuilderDisputes />
      </RoleGuard>
    </AppShell>
  );
}
