"use client";

import { useEffect, useState } from "react";
import { api, apiBlob } from "@/lib/api";
import type { ReceiptDTO, ReceiptSettlement } from "@/lib/types";
import { Button } from "@/components/ui/Button";
import { Skeleton } from "@/components/ui/Skeleton";

/** "Paid 8.50 cr to the builder" in the receipt's own figures (never recomputed here). */
export function settledLine(s: ReceiptSettlement): string {
  switch (s.outcome) {
    case "ACCEPT":
      return `${s.paid} cr paid to the builder`;
    case "SPLIT":
      return `${s.paid} cr paid to the builder, ${s.refunded} cr refunded`;
    default:
      return `${s.refunded} cr refunded`;
  }
}

/**
 * The task's signed receipts (D40): one per settled Item, each the platform's Ed25519-signed record
 * of the Contract, the fingerprints of what was asked for and delivered, the checks, any ruling and
 * the ledger entries. The download is the exact signed document, checkable against the public keys
 * at `/api/receipt-keys` without trusting the platform's database. `refreshKey` refetches when
 * something settles.
 */
export function ReceiptsPanel({ taskId, refreshKey }: { taskId: string; refreshKey: string }) {
  const [attempt, setAttempt] = useState(0);
  const key = `${taskId}#${refreshKey}#${attempt}`;
  const [settled, setSettled] = useState<{ key: string; receipts: ReceiptDTO[] | null; error: boolean } | null>(null);
  const [downloading, setDownloading] = useState<string | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api<ReceiptDTO[]>(`/tasks/${taskId}/receipts`)
      .then((receipts) => {
        if (!cancelled) setSettled({ key, receipts, error: false });
      })
      .catch(() => {
        if (!cancelled) setSettled({ key, receipts: null, error: true });
      });
    return () => {
      cancelled = true;
    };
  }, [taskId, key]);

  const current = settled?.key === key ? settled : null;
  // Keep showing the last list while a refresh is in flight, so the panel doesn't flash.
  const receipts = current?.receipts ?? (settled?.receipts && !current ? settled.receipts : null);
  const loading = !current && !receipts;
  const many = (receipts?.length ?? 0) > 1;

  async function download(r: ReceiptDTO) {
    setDownloading(r.id);
    setDownloadError(null);
    try {
      const blob = await apiBlob(`/receipts/${r.id}`);
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = r.itemNo == null ? `hireai-receipt-${r.taskId}.json` : `hireai-receipt-${r.taskId}-item-${r.itemNo}.json`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch {
      setDownloadError("Couldn't download the receipt. Check your connection and try again.");
    } finally {
      setDownloading(null);
    }
  }

  return (
    <section aria-labelledby="receipts-h" className="docket overflow-hidden">
      <div className="px-5 pb-3 pt-5">
        <h2 id="receipts-h" className="eyebrow">
          {many ? "Signed receipts" : "Signed receipt"}
        </h2>
        <p className="mt-1 max-w-2xl text-sm text-muted">
          The platform&apos;s signed record of how this work settled: the contract, fingerprints of what was asked for
          and what was delivered, every check, any ruling, and the ledger entries.{" "}
          {many ? "Each settled Item has its own." : null} Anyone you share it with can check it against
          HireAI&apos;s published key, without having to trust our records.
        </p>
      </div>
      <div className="docket-tear" />

      {loading && (
        <div role="status" className="space-y-2 px-5 py-4">
          <span className="sr-only">Loading the receipts</span>
          <Skeleton className="h-10 w-full rounded-md" />
        </div>
      )}

      {current?.error && !receipts && (
        <div role="alert" className="flex flex-wrap items-center gap-3 px-5 py-4 text-sm">
          <span className="text-red">Couldn&apos;t load the receipts. Check your connection and try again.</span>
          <Button variant="ghost" size="sm" onClick={() => setAttempt((n) => n + 1)}>
            Try again
          </Button>
        </div>
      )}

      {receipts && receipts.length === 0 && (
        <div className="flex flex-wrap items-center gap-3 px-5 py-4 text-sm text-muted">
          <span>No receipt yet: one is issued as soon as the work settles.</span>
          <Button variant="ghost" size="sm" onClick={() => setAttempt((n) => n + 1)}>
            Check again
          </Button>
        </div>
      )}

      {receipts && receipts.length > 0 && (
        <ul className="divide-y divide-line">
          {receipts.map((r) => (
            <li key={r.id} className="flex flex-wrap items-center justify-between gap-3 px-5 py-3">
              <div className="min-w-0 space-y-0.5">
                <p className="text-sm text-fg">
                  {r.itemNo != null && <span className="mr-2 font-semibold">Item {r.itemNo}</span>}
                  <span className="tabular">{settledLine(r.receipt.settlement)}</span>
                </p>
                <p className="text-xs text-dim">
                  Signed <time dateTime={r.issuedAt}>{new Date(r.issuedAt).toLocaleString()}</time> with key{" "}
                  <span className="break-all font-mono">{r.keyId}</span>
                </p>
              </div>
              <Button
                variant="secondary"
                size="sm"
                onClick={() => download(r)}
                disabled={downloading === r.id}
                aria-label={r.itemNo == null ? "Download receipt" : `Download receipt for Item ${r.itemNo}`}
              >
                {downloading === r.id ? "Preparing…" : "Download receipt"}
              </Button>
            </li>
          ))}
        </ul>
      )}

      <div className="space-y-1 border-t border-line px-5 py-3 text-xs text-dim">
        {downloadError && (
          <p role="alert" className="text-red">
            {downloadError}
          </p>
        )}
        <p>
          Verification keys:{" "}
          <a
            href="/api/receipt-keys"
            target="_blank"
            rel="noreferrer noopener"
            className="font-medium text-accent underline-offset-4 hover:underline"
          >
            /api/receipt-keys ↗
          </a>{" "}
          · Ed25519 over RFC 8785 JSON
        </p>
      </div>
    </section>
  );
}
