"use client";

import { RichText } from "@/components/RichText";
import { REJECT_LABEL } from "@/components/DisputeProgressPanel";
import type { RejectReason } from "@/lib/types";
import { useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { AppShell } from "@/components/AppShell";
import { RoleGuard } from "@/components/RoleGuard";
import { Card, Badge, Button, Field, SkeletonRows } from "@/components/ui";
import type { AdminDisputeDetailDTO, RulingCategory } from "@/lib/types";
import { DECIDED_BY, RULING_STAMP, rationaleText } from "@/lib/rulings";
import { DisputeTrack } from "@/components/DisputeTrack";

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
      <pre className="overflow-auto whitespace-pre-wrap break-words rounded-md border border-line bg-surface-2 p-4 font-mono text-xs leading-relaxed text-fg">
        {body}
      </pre>
    </div>
  );
}

function Meta({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-xs text-dim">{label}</dt>
      <dd className="mt-0.5 text-xs text-fg">{value}</dd>
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
        if (!cancelled)
          setError(
            e instanceof ApiError ? e.message : "Failed to load dispute",
          );
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
      const updated = await api<AdminDisputeDetailDTO>(
        `/admin/disputes/${id}/rule`,
        {
          method: "POST",
          body: JSON.stringify({
            category,
            // The API needs a note; without one, say so in words rather than repeating the category.
            rationale: rationale.trim() || "Ruled by the administrator without a written note.",
          }),
        },
      );
      setDetail(updated);
    } catch (e) {
      setError(
        e instanceof ApiError ? e.message : "Failed to submit the ruling",
      );
    } finally {
      setBusy(false);
    }
  }

  if (error && !detail) {
    return (
      <Card>
        <p role="alert" className="text-sm text-red">
          {error}
        </p>
      </Card>
    );
  }
  if (!detail) {
    return (
      <Card>
        <SkeletonRows label="Loading dispute…" rows={3} />
      </Card>
    );
  }

  const p = detail.settlementPreview;
  function outcomeHint(cat: RulingCategory): string {
    if (!p) return "";
    if (cat === "FULFILLED")
      return `builder +${p.fulfilledPayout} cr · ${p.fulfilledCommission} cr commission`;
    if (cat === "NOT_FULFILLED")
      return `client refunded ${p.notFulfilledRefund} cr in full`;
    return `builder +${p.partialBuilderNet} cr · client refunded ${p.partialClientRefund} cr`;
  }

  const escalatedNoRuling = detail.actionable && detail.rulings.length === 0;

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <Link
        href="/admin/disputes"
        className="inline-block text-sm font-medium text-muted transition hover:text-accent"
      >
        ← Disputes
      </Link>

      <Card className="space-y-5">
        <header className="flex items-start justify-between gap-4">
          <div>
            <p className="font-mono text-xs text-dim">
              Dispute #{detail.disputeId.slice(0, 8)}
            </p>
            <h1 className="display mt-1 text-[2.2rem]">{detail.taskTitle}</h1>
            <dl className="mt-3 flex flex-wrap gap-x-8 gap-y-2 text-sm">
              <div>
                <dt className="text-xs text-dim">Reason</dt>
                <dd className="font-semibold">
                  {REJECT_LABEL[detail.reasonCategory as RejectReason] ??
                    detail.reasonCategory}
                </dd>
              </div>
              <div>
                <dt className="text-xs text-dim">Raised by</dt>
                <dd className="font-semibold">{detail.clientName}</dd>
              </div>
              <div>
                <dt className="text-xs text-dim">{detail.status === "RESOLVED" ? "Settled from escrow" : "In escrow"}</dt>
                <dd className="font-semibold text-accent">
                  {detail.budget} cr
                </dd>
              </div>
            </dl>
          </div>
          <Badge status={detail.status} />
        </header>

        {/* where the case is in its tiers */}
        <div className="-mx-2 overflow-x-auto px-2 pb-1">
          <div className="min-w-[26rem]">
            <DisputeTrack status={detail.status} rulings={detail.rulings} />
          </div>
        </div>

        {/* Task submission + agent context */}
        <section className="rounded-md border border-line bg-surface-2 p-4">
          <p className="eyebrow mb-3">Submission &amp; agent</p>
          <dl className="grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-3">
            <Meta label="Budget" value={`${detail.budget} cr`} />
            <Meta label="Category" value={detail.category ?? "—"} />
            <Meta label="Output format" value={detail.outputFormat ?? "—"} />
            <Meta label="Submitted" value={fmt(detail.submittedAt)} />
            <Meta
              label="Result received"
              value={fmt(detail.resultReceivedAt)}
            />
            <Meta label="Agent reported" value={detail.agentStatus ? detail.agentStatus.toLowerCase().replace(/_/g, " ") : "—"} />
            <Meta label="Agent" value={detail.agentName ?? "—"} />
            <Meta label="Builder" value={detail.builderName ?? "—"} />
            <Meta
              label="Agent reputation"
              value={
                detail.agentReputation == null
                  ? "—"
                  : `${detail.agentReputation} / 100`
              }
            />
          </dl>
        </section>

        <h2 className="border-t border-line pt-6 text-xl font-bold text-fg">
          The case
        </h2>

        {/* Client's complaint — the "why" behind the dispute */}
        <div className="space-y-1">
          <p className="eyebrow">Client&apos;s complaint</p>
          <p className="rounded-md border border-line border-l-4 border-l-amber bg-surface-2 p-4 text-sm text-fg">
            {detail.clientReason
              ? `“${detail.clientReason}”`
              : "No detail provided beyond the category."}
          </p>
        </div>

        <div className="space-y-1">
          <p className="eyebrow">Task description (what the client asked)</p>
          <RichText
            text={detail.taskDescription}
            className="max-h-80 overflow-auto rounded-md border border-line bg-surface-2 p-4 text-sm leading-relaxed text-muted"
          />
        </div>

        <h2 className="border-t border-line pt-6 text-xl font-bold text-fg">
          The evidence
        </h2>
        <Evidence
          label="Output spec (the binding contract)"
          body={prettyJson(detail.outputSpecJson)}
        />
        <Evidence
          label="Agent result (what was delivered)"
          body={prettyJson(detail.resultPayloadJson)}
        />
        {detail.resultUrl && (
          <a
            href={detail.resultUrl}
            target="_blank"
            rel="noreferrer"
            className="inline-flex text-sm font-semibold text-accent hover:underline"
          >
            Open deliverable
          </a>
        )}

        {/* Arbitration / ruling history */}
        <section className="space-y-3">
          <h2 className="border-t border-line pt-6 text-xl font-bold text-fg">
            Arbitration and ruling history
          </h2>
          {detail.rulings.length > 0 ? (
            <ol className="relative space-y-3 border-l-2 border-line pl-5">
              {detail.rulings.map((r, idx) => {
                const st = RULING_STAMP[r.category];
                const note = rationaleText(r.rationale);
                const last = idx === detail.rulings.length - 1;
                return (
                  <li key={idx} className="relative">
                    <span
                      aria-hidden
                      className={`absolute -left-[1.6rem] top-3 size-3 rounded-full border-2 border-surface ${last ? "bg-accent" : "bg-line-bright"}`}
                    />
                    <div className="flex items-start justify-between gap-3 rounded-lg border border-line bg-surface-2 px-4 py-3">
                      <div className="min-w-0 flex-1">
                        <p className="text-sm font-semibold text-fg">
                          {DECIDED_BY[r.decidedBy]}
                          <span className="font-normal text-dim"> · tier {r.tier}</span>
                        </p>
                        <p className={`mt-0.5 text-sm font-medium ${st.tone}`}>{st.money}</p>
                        {note && <p className="mt-1 text-sm text-muted">&ldquo;{note}&rdquo;</p>}
                      </div>
                      <span
                        aria-hidden
                        style={last && detail.status === "RESOLVED" ? { animationDelay: "250ms" } : undefined}
                        className={`stamp shrink-0 text-[0.95rem] ${st.tone} ${last && detail.status === "RESOLVED" ? "inspect-stamp" : "opacity-60"}`}
                      >
                        {st.stamp}
                      </span>
                      <span className="sr-only">Ruling: {st.stamp}.</span>
                    </div>
                  </li>
                );
              })}
            </ol>
          ) : escalatedNoRuling ? (
            <p className="text-xs text-dim">
              Arbitration didn&apos;t complete — no ruling was produced (the
              arbitrator dead-lettered or timed out). That&apos;s why this is on
              your desk.
            </p>
          ) : (
            <p className="text-xs text-dim">No ruling recorded.</p>
          )}
        </section>

        {detail.actionable ? (
          <section
            aria-label="Issue ruling"
            className="space-y-4 border-t border-line pt-5"
          >
            <p className="eyebrow">Backstop ruling</p>
            <p className="text-sm text-muted">
              No money has settled yet. Your ruling settles escrow once,
              deterministically, from the category:
            </p>
            {error && (
              <p role="alert" className="text-xs text-red">
                {error}
              </p>
            )}
            <Field label="Outcome" hint="Required">
              <div className="space-y-2" role="radiogroup">
                {CATEGORIES.map(({ value, label }) => (
                  <label
                    key={value}
                    className="flex cursor-pointer items-start gap-3 rounded-md border border-line bg-surface px-3 py-2.5 text-sm text-fg hover:border-accent/50"
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
                      <span className="block text-xs text-dim">
                        {outcomeHint(value)}
                      </span>
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
                className="w-full rounded-md border border-line-bright bg-surface p-3 text-sm text-fg"
              />
            </Field>
            <Button
              variant="danger"
              onClick={submit}
              disabled={busy || !category}
            >
              Issue ruling
            </Button>
          </section>
        ) : (
          <div className="flex items-start gap-3 border-t border-line pt-5">
            {/* the status badge is already in the header; this says what it means for the admin */}
            <svg viewBox="0 0 16 16" className="mt-0.5 size-4 shrink-0 text-dim" aria-hidden>
              <rect x="3" y="7" width="10" height="7" rx="1.5" fill="none" stroke="currentColor" strokeWidth="1.5" />
              <path d="M5.5 7V5a2.5 2.5 0 0 1 5 0v2" fill="none" stroke="currentColor" strokeWidth="1.5" />
            </svg>
            <p className="text-sm text-muted">
              {detail.status === "RESOLVED"
                ? "A ruling has been recorded and the credits settled. This case can no longer be changed."
                : "Nothing for you to do yet. The arbitrator is still working on this case; it comes to you only if it is escalated."}
            </p>
          </div>
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
