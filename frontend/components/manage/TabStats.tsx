"use client";

import { useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";
import type { AgentStatsDTO } from "@/lib/types";
import { StatTile } from "@/components/StatTile";
import { Sparkline } from "@/components/Sparkline";
import { Badge } from "@/components/ui/Badge";

interface Props {
  agentId: string;
}

function fmt(seconds: number | null): string {
  if (seconds == null) return "—";
  return `${seconds}s`;
}

function fmtPct(rate: number | null): string {
  if (rate == null) return "—";
  return `${Math.round(rate * 100)}%`;
}

export function TabStats({ agentId }: Props) {
  const [stats, setStats] = useState<AgentStatsDTO | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<AgentStatsDTO>(`/agents/${agentId}/stats`)
      .then(setStats)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Failed to load stats"));
  }, [agentId]);

  if (error) {
    return (
      <p role="alert" className="font-mono text-xs text-red">
        {error}
      </p>
    );
  }

  if (!stats) {
    return <p className="font-mono text-sm text-dim">Loading…</p>;
  }

  const { volume, performance, earnings } = stats;

  return (
    <div className="space-y-8">
      {/* Stat tile grid */}
      <div className="grid grid-cols-2 gap-px overflow-hidden rounded-xl border border-line bg-line sm:grid-cols-4">
        <StatTile value={volume.total} label="Total" />
        <StatTile value={volume.completed} label="Completed" tone="accent" />
        <StatTile
          value={volume.failed}
          label="Failed"
          tone={volume.failed > 0 ? "red" : "fg"}
        />
        <StatTile
          value={volume.open}
          label="Open"
          tone={volume.open > 0 ? "amber" : "fg"}
        />
        <StatTile
          value={volume.successRate == null ? "—" : `${Math.round(volume.successRate * 100)}%`}
          label="Success %"
          tone={volume.successRate != null && volume.successRate >= 0.8 ? "accent" : "fg"}
        />
        <StatTile value={fmt(performance.avgTurnaroundSeconds)} label="Avg turnaround" />
        <StatTile value={fmtPct(performance.onTimeRate)} label="On-time %" />
        <StatTile value={`${earnings.creditsInEscrow} cr`} label="Escrow" tone="amber" />
      </div>

      <p className="font-mono text-[0.6rem] uppercase tracking-wider text-dim">
        Earnings are escrowed amounts — settlement lands with Module 5.
      </p>

      {/* Sparkline */}
      <div>
        <p className="eyebrow mb-3">Requests · last 14 days</p>
        <Sparkline points={stats.trend} />
      </div>

      {/* Recent tasks */}
      <div>
        <p className="eyebrow mb-3">Recent tasks</p>
        <ul className="space-y-2">
          {stats.recentTasks.map((t) => (
            <li
              key={t.id}
              className="flex items-center justify-between gap-3 rounded-md border border-line bg-surface-2 px-4 py-3"
            >
              <p className="truncate text-sm text-fg">{t.title}</p>
              <div className="flex shrink-0 items-center gap-3">
                <Badge status={t.status}>{t.status}</Badge>
                <span className="font-mono text-[0.6rem] text-dim">
                  {new Date(t.createdAt).toLocaleDateString()}
                </span>
              </div>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
