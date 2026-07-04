"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { AppShell } from "@/components/AppShell";
import { RoleGuard } from "@/components/RoleGuard";
import { Card, Badge, Button, Field } from "@/components/ui";
import type { AdminDisputeDetailDTO, RulingCategory } from "@/lib/types";

const CATEGORIES: { value: RulingCategory; label: string }[] = [
  { value: "FULFILLED", label: "Fulfilled — pay the builder" },
  { value: "PARTIALLY_FULFILLED", label: "Partially fulfilled — split" },
  { value: "NOT_FULFILLED", label: "Not fulfilled — full refund" },
];

function prettyJson(raw: string | null): string {
  if (!raw) return "—";
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

function fmt(ts: string | null): string {
  return ts ? new Date(ts).toLocaleString() : "—";
}

function Evidence({ label, body }: { label: string; body: string }) {
  return (
    <div className="space-y-1">
      <p className="eyebrow">{label}</p>
      <pre className="overflow-auto rounded-md border border-line bg-canvas p-3 font-mono text-xs leading-relaxed text-fg">
        {body}
      </pre>
    </div>
  );
}

function Meta({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="font-mono text-[0.6rem] uppercase tracking-[0.18em] text-dim">{label}</dt>
      <dd className="mt-0.5 font-mono text-xs text-fg">{value}</dd>
    </div>
  );
}

function AdminDisputeDetail() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const [detail, setDetail] = useState<AdminDisputeDetailDTO | null>(null);
  const [category, setCategory] = useState<RulingCategory | null>(null);
  const [rationale, setRationale] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    api<AdminDisputeDetailDTO>(`/admin/disputes/${id}`)
      .then((d) => {
        if (!cancelled) setDetail(d);
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof ApiError ? e.message : "Failed to load dispute");
      });
    return () => {
      cancelled = true;
    };
  }, [id]);

  async function submit() {
    if (!category) return;
    setBusy(true);
    setError(null);
    try {
      const updated = await api<AdminDisputeDetailDTO>(`/admin/disputes/${id}/rule`, {
        method: "POST",
        body: JSON.stringify({ category, rationale: rationale.trim() || category }),
      });
      setDetail(updated);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Failed to submit the ruling");
    } finally {
      setBusy(false);
    }
  }

  if (error && !detail) {
    return (
      <Card>
        <p role="alert" className="font-mono text-sm text-red">
          {error}
        </p>
      </Card>
    );
  }
  if (!detail) {
    return (
      <Card>
        <p className="font-mono text-sm text-dim">Loading dispute…</p>
      </Card>
    );
  }

  const p = detail.settlementPreview;
  function outcomeHint(cat: RulingCategory): string {
    if (!p) return "";
    if (cat === "FULFILLED") return `builder +${p.fulfilledPayout} cr · ${p.fulfilledCommission} cr commission`;
    if (cat === "NOT_FULFILLED") return `client refunded ${p.notFulfilledRefund} cr in full`;
    return `builder +${p.partialBuilderNet} cr · client refunded ${p.partialClientRefund} cr`;
  }

  const escalatedNoRuling = detail.actionable && detail.rulings.length === 0;

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <Link href="/admin/disputes" className="font-mono text-xs text-dim transition hover:text-accent">
        ← disputes
      </Link>

      <Card className="space-y-5">
        <header className="flex items-start justify-between gap-4">
          <div>
            <h1 className="text-xl font-extrabold tracking-tight">{detail.taskTitle}</h1>
            <p className="mt-1 font-mono text-xs text-dim">
              #{detail.disputeId.slice(0, 8)} · reason {detail.reasonCategory} · raised by {detail.clientName} ·{" "}
              <span className="text-accent">{detail.budget} cr</span> in escrow
            </p>
          </div>
          <Badge status={detail.status}>{detail.status}</Badge>
        </header>

        {/* Task submission + agent context */}
        <section className="rounded-md border border-line bg-surface-2 p-4">
          <p className="eyebrow mb-3">Submission &amp; agent</p>
          <dl className="grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-3">
            <Meta label="Budget" value={`${detail.budget} cr`} />
            <Meta label="Category" value={detail.category ?? "—"} />
            <Meta label="Output format" value={detail.outputFormat ?? "—"} />
            <Meta label="Submitted" value={fmt(detail.submittedAt)} />
            <Meta label="Result received" value={fmt(detail.resultReceivedAt)} />
            <Meta label="Agent status" value={detail.agentStatus ?? "—"} />
            <Meta label="Agent" value={detail.agentName ?? "—"} />
            <Meta label="Builder" value={detail.builderName ?? "—"} />
            <Meta
              label="Agent reputation"
              value={detail.agentReputation == null ? "—" : `${detail.agentReputation} / 100`}
            />
          </dl>
        </section>

        {/* Client's complaint — the "why" behind the dispute */}
        <div className="space-y-1">
          <p className="eyebrow">Client&apos;s complaint</p>
          <p className="rounded-md border border-amber/30 bg-amber/10 p-3 text-sm text-fg">
            {detail.clientReason ? `“${detail.clientReason}”` : "No detail provided beyond the category."}
          </p>
        </div>

        <Evidence label="Task description (what the client asked)" body={detail.taskDescription} />
        <Evidence label="Output spec (the binding contract)" body={prettyJson(detail.outputSpecJson)} />
        <Evidence label="Agent result (what was delivered)" body={prettyJson(detail.resultPayloadJson)} />
        {detail.resultUrl && (
          <a
            href={detail.resultUrl}
            target="_blank"
            rel="noreferrer"
            className="inline-flex font-mono text-xs font-semibold text-accent hover:underline"
          >
            Open deliverable →
          </a>
        )}

        {/* Arbitration / ruling history */}
        <section className="space-y-2 border-t border-line pt-5">
          <p className="eyebrow">Arbitration &amp; ruling history</p>
          {detail.rulings.length > 0 ? (
            detail.rulings.map((r, idx) => (
              <div key={idx} className="rounded-md border border-line bg-surface-2 p-3 font-mono text-xs text-muted">
                <span className="text-accent">{r.decidedBy}</span> · tier {r.tier} · {r.category}
                {r.rationale ? <span className="block text-dim">“{r.rationale}”</span> : null}
              </div>
            ))
          ) : escalatedNoRuling ? (
            <p className="font-mono text-xs text-dim">
              Arbitration didn&apos;t complete — no ruling was produced (the arbitrator dead-lettered or timed
              out). That&apos;s why this is on your desk.
            </p>
          ) : (
            <p className="font-mono text-xs text-dim">No ruling recorded.</p>
          )}
        </section>

        {detail.actionable ? (
          <section aria-label="Issue ruling" className="space-y-4 border-t border-line pt-5">
            <p className="eyebrow">Backstop ruling</p>
            <p className="text-sm text-muted">
              No money has settled yet. Your ruling settles escrow once, deterministically, from the category:
            </p>
            {error && (
              <p role="alert" className="font-mono text-xs text-red">
                {error}
              </p>
            )}
            <Field label="Outcome" hint="Required">
              <div className="space-y-2" role="radiogroup">
                {CATEGORIES.map(({ value, label }) => (
                  <label
                    key={value}
                    className="flex cursor-pointer items-start gap-3 rounded-md border border-line bg-canvas px-3 py-2.5 font-mono text-xs text-fg hover:border-line-bright"
                  >
                    <input
                      type="radio"
                      name="admin-ruling"
                      value={value}
                      aria-label={label}
                      checked={category === value}
                      onChange={() => setCategory(value)}
                      className="mt-0.5 accent-accent"
                    />
                    <span>
                      {label}
                      <span className="block text-[0.65rem] text-dim">{outcomeHint(value)}</span>
                    </span>
                  </label>
                ))}
              </div>
            </Field>
            <Field label="Rationale" htmlFor="admin-rationale">
              <textarea
                id="admin-rationale"
                aria-label="rationale"
                maxLength={1000}
                rows={3}
                value={rationale}
                onChange={(e) => setRationale(e.target.value)}
                className="w-full rounded-md border border-line bg-canvas p-3 font-mono text-xs text-fg"
              />
            </Field>
            <Button variant="danger" onClick={submit} disabled={busy || !category}>
              Issue ruling
            </Button>
          </section>
        ) : (
          <p className="border-t border-line pt-5 font-mono text-xs text-dim">
            This dispute is {detail.status} — read-only.
          </p>
        )}
      </Card>
    </div>
  );
}

export default function Page() {
  return (
    <AppShell>
      <RoleGuard role="ADMIN">
        <AdminDisputeDetail />
      </RoleGuard>
    </AppShell>
  );
}
