"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { api, apiUpload, ApiError } from "@/lib/api";
import type { EngagementDTO, SourceDTO } from "@/lib/types";
import { Button, Select, Skeleton } from "@/components/ui";
import { formatBytes } from "@/components/DeliverablePanel";

/** What the booking sends: the Engagement and the documents chosen as Items, in list order. */
export interface DocumentSelection {
  engagementId: string | null;
  sourceIds: string[];
}

const RETENTION_CHOICES = [30, 14, 7, 1];

/** "Downloaded through its link 2× · last 10:04" / "Not downloaded yet" / refused attempts, from the download log. */
export function downloadSummary(doc: SourceDTO): string {
  const downloads = doc.downloads ?? [];
  const served = downloads.filter((d) => d.outcome === "SERVED");
  const refused = downloads.length - served.length;
  const parts: string[] = [];
  if (served.length === 0) {
    parts.push("Not downloaded yet");
  } else {
    const last = new Date(served[served.length - 1].at);
    parts.push(`Downloaded through its link ${served.length}× · last ${last.toLocaleString()}`);
  }
  if (refused > 0) parts.push(`${refused} refused (link expired)`);
  return parts.join(" · ");
}

/**
 * Confidential documents for a booking (spec S4, ADR 0010). They can be sent only under an Engagement:
 * the buyer's recorded consent to send this agent its documents. Without one, the panel offers to start
 * it; with an active one, it uploads documents (upload only, into private storage) and lets the buyer
 * choose which become the Task's Items. Each document shows its hash and the log of the agent's downloads.
 */
export function EngagementDocuments({ agentId, agentName, onChange }: {
  agentId: string;
  agentName: string;
  onChange: (selection: DocumentSelection) => void;
}) {
  const [engagement, setEngagement] = useState<EngagementDTO | null | undefined>(undefined);
  const [docs, setDocs] = useState<SourceDTO[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoadError(null);
    try {
      const mine = await api<EngagementDTO[]>("/engagements");
      const open = mine.find((e) => e.agentId === agentId && e.status !== "ENDED") ?? null;
      setEngagement(open);
      if (open && open.status === "ACTIVE") {
        // Oldest first, so the list reads in the order the documents become Items.
        setDocs((await api<SourceDTO[]>(`/engagements/${open.id}/sources`)).slice().reverse());
      }
    } catch (e) {
      setLoadError(e instanceof ApiError ? e.message : "Could not load your Engagement with this agent");
    }
  }, [agentId]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- loading the Engagement on mount
    void load();
  }, [load]);

  useEffect(() => {
    onChange({ engagementId: engagement?.status === "ACTIVE" ? engagement.id : null, sourceIds: selected });
  }, [engagement, selected, onChange]);

  return (
    <section aria-labelledby="docs-h" className="space-y-3 rounded-lg border border-line bg-surface p-4">
      <div>
        <h2 id="docs-h" className="eyebrow">Confidential documents</h2>
        <p className="mt-1 text-sm text-muted">
          Optional. Send documents, such as invoices, as the Items of this task. Each one is checked and
          settled on its own.
        </p>
      </div>
      {loadError ? (
        <p role="alert" className="rounded-md border border-red/30 bg-red/10 px-3 py-2 text-xs text-red">
          {loadError}{" "}
          <button type="button" className="font-semibold underline" onClick={() => void load()}>Retry</button>
        </p>
      ) : engagement === undefined ? (
        <div role="status" aria-label="Loading your Engagement" className="space-y-2">
          <Skeleton className="h-4 w-2/3" />
          <Skeleton className="h-10 w-full" />
        </div>
      ) : engagement === null ? (
        <StartEngagement agentId={agentId} agentName={agentName} onStarted={(e) => { setEngagement(e); setDocs([]); }} />
      ) : engagement.status === "AWAITING_AGENT_ACCEPTANCE" ? (
        <p role="status" className="rounded-md border border-amber/35 bg-amber/10 px-3 py-2 text-sm text-amber">
          Your Engagement is waiting for {agentName}&apos;s builder to accept its terms. You can upload
          documents once they do.
        </p>
      ) : (
        <ActiveEngagement
          engagement={engagement}
          docs={docs}
          selected={selected}
          onUploaded={(doc) => {
            setDocs((d) => [...d, doc]);
            setSelected((s) => [...s, doc.id]);
          }}
          onToggle={(id) => setSelected((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]))}
        />
      )}
    </section>
  );
}

function StartEngagement({ agentId, agentName, onStarted }: {
  agentId: string;
  agentName: string;
  onStarted: (e: EngagementDTO) => void;
}) {
  const [consent, setConsent] = useState(false);
  const [retentionDays, setRetentionDays] = useState(30);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function start() {
    setBusy(true);
    setError(null);
    try {
      onStarted(await api<EngagementDTO>("/engagements", {
        method: "POST",
        body: JSON.stringify({ agentId, confidentialConsent: true, retentionDays }),
      }));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not start the Engagement");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-3">
      <p className="text-sm text-fg">
        Documents go only to an agent you chose, under an <strong>Engagement</strong>: a standing
        agreement that records your consent. Marketplace tasks, where the platform picks the agent, take
        public material only.
      </p>
      <ul className="list-disc space-y-1 pl-5 text-xs text-muted">
        <li>Uploaded into private storage and frozen with a SHA-256 hash.</li>
        <li>The agent gets a download link per document that expires at its delivery deadline. Every download is logged here.</li>
        <li>Deleted after the last review and dispute window closes, plus the period you choose. The hash is kept.</li>
      </ul>
      <label className="flex items-start gap-2 text-sm text-fg">
        <input
          type="checkbox"
          className="mt-0.5 size-4 accent-[var(--color-accent)]"
          checked={consent}
          onChange={(e) => setConsent(e.target.checked)}
        />
        <span>I consent to sending my confidential documents to {agentName}.</span>
      </label>
      <div className="flex flex-wrap items-end gap-3">
        <label className="text-xs text-dim">
          Keep documents for
          <Select
            aria-label="Keep documents for"
            className="mt-1 w-40"
            value={retentionDays}
            onChange={(e) => setRetentionDays(Number(e.target.value))}
          >
            {RETENTION_CHOICES.map((d) => (
              <option key={d} value={d}>{d} {d === 1 ? "day" : "days"}</option>
            ))}
          </Select>
        </label>
        <Button type="button" variant="secondary" disabled={!consent || busy} onClick={() => void start()}>
          {busy ? "Starting…" : "Start an Engagement"}
        </Button>
      </div>
      {error && <p role="alert" className="text-xs text-red">{error}</p>}
    </div>
  );
}

function ActiveEngagement({ engagement, docs, selected, onUploaded, onToggle }: {
  engagement: EngagementDTO;
  docs: SourceDTO[];
  selected: string[];
  onUploaded: (doc: SourceDTO) => void;
  onToggle: (id: string) => void;
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState<string | null>(null);
  const [errors, setErrors] = useState<string[]>([]);

  async function upload(files: FileList) {
    setErrors([]);
    const failed: string[] = [];
    for (const file of Array.from(files)) {
      setUploading(file.name);
      try {
        const form = new FormData();
        form.append("file", file);
        onUploaded(await apiUpload<SourceDTO>(`/engagements/${engagement.id}/sources`, form));
      } catch (e) {
        failed.push(`${file.name}: ${e instanceof ApiError ? e.message : "upload failed"}`);
      }
    }
    setUploading(null);
    setErrors(failed);
    if (inputRef.current) inputRef.current.value = "";
  }

  return (
    <div className="space-y-3">
      <p className="text-xs text-dim">
        <span className="font-semibold text-green">Engagement active</span> · consent recorded{" "}
        {new Date(engagement.consentedAt).toLocaleDateString()} · documents kept {engagement.retentionDays}{" "}
        {engagement.retentionDays === 1 ? "day" : "days"} after the last window closes
      </p>
      <input
        ref={inputRef}
        type="file"
        multiple
        aria-label="Documents to upload"
        className="sr-only"
        onChange={(e) => e.target.files && e.target.files.length > 0 && void upload(e.target.files)}
        disabled={uploading != null}
      />
      <button
        type="button"
        disabled={uploading != null}
        onClick={() => inputRef.current?.click()}
        className="flex w-full items-center gap-3 rounded-lg border-2 border-dashed border-line-bright bg-surface-2 px-4 py-3 text-left transition hover:border-accent/60 hover:bg-accent/[0.03] disabled:cursor-wait disabled:opacity-60"
      >
        <svg viewBox="0 0 20 20" className="size-5 shrink-0 text-accent" aria-hidden>
          <path d="M10 13V4m0 0L6.5 7.5M10 4l3.5 3.5M4 13v2.5h12V13" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
        <span className="text-sm">
          <span className="font-semibold text-fg">{uploading ? `Uploading ${uploading}…` : "Upload documents"}</span>
          <span className="block text-xs text-dim">Any file up to 25 MB. Uploaded only: the platform never fetches a link.</span>
        </span>
      </button>
      {errors.length > 0 && (
        <div role="alert" className="space-y-1 text-xs text-red">
          {errors.map((e) => <p key={e}>{e}</p>)}
        </div>
      )}
      {docs.length === 0 ? (
        <p className="text-xs text-dim">No documents yet. Each one you upload becomes an Item of this task.</p>
      ) : (
        <ul aria-label="Your documents" className="divide-y divide-line overflow-hidden rounded-lg border border-line">
          {docs.map((doc) => {
            const retired = doc.contentDeletedAt != null;
            const checked = selected.includes(doc.id);
            const itemNo = selected.indexOf(doc.id) + 1;
            return (
              <li key={doc.id} className={`flex items-start gap-3 px-3 py-2.5 ${retired ? "opacity-60" : ""}`}>
                <input
                  type="checkbox"
                  className="mt-1 size-4 shrink-0 accent-[var(--color-accent)]"
                  aria-label={`Send ${doc.filename} as an Item`}
                  checked={checked}
                  disabled={retired}
                  onChange={() => onToggle(doc.id)}
                />
                <div className="min-w-0 flex-1">
                  <p className="flex flex-wrap items-baseline gap-x-2 text-sm">
                    <span className="break-all font-semibold text-fg">{doc.filename}</span>
                    {checked && <span className="tabular font-mono text-xs font-bold text-accent">Item #{String(itemNo).padStart(2, "0")}</span>}
                  </p>
                  <p className="text-xs text-dim">
                    {doc.mediaType} · {formatBytes(doc.sizeBytes)} ·{" "}
                    <span className="font-mono" title={doc.sha256}>sha256 {doc.sha256.slice(0, 12)}…</span>
                  </p>
                  <p className="text-xs text-muted">
                    {retired ? "Deleted after its retention period; only the hash is kept" : downloadSummary(doc)}
                  </p>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
