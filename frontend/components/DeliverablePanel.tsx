"use client";

import { useEffect, useState } from "react";
import { ApiError, apiBlob } from "@/lib/api";
import type { DeliverableDTO } from "@/lib/types";
import { Button } from "@/components/ui/Button";
import { Skeleton } from "@/components/ui/Skeleton";

const TYPE_LABEL: Record<string, string> = {
  "image/png": "PNG image",
  "image/jpeg": "JPEG image",
  "image/webp": "WebP image",
  "image/gif": "GIF image",
  "application/pdf": "PDF document",
  "application/zip": "ZIP archive",
  "application/json": "JSON file",
  "text/csv": "CSV file",
  "text/plain": "Plain text",
  "text/html": "HTML page",
  "application/octet-stream": "Unrecognised binary file",
};

const PREVIEWABLE = new Set(["image/png", "image/jpeg", "image/webp"]);

const EXTENSION: Record<string, string> = {
  "image/png": "png",
  "image/jpeg": "jpg",
  "image/webp": "webp",
  "application/pdf": "pdf",
  "application/zip": "zip",
  "application/json": "json",
  "text/csv": "csv",
  "text/plain": "txt",
};

export function typeLabel(mediaType: string): string {
  return TYPE_LABEL[mediaType] ?? mediaType;
}

/** 1536 → "1.5 KB". */
export function formatBytes(n: number): string {
  if (n < 1024) return `${n} ${n === 1 ? "byte" : "bytes"}`;
  const units = ["KB", "MB", "GB"];
  let v = n / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(v < 10 ? 1 : 0)} ${units[i]}`;
}

function errorText(e: unknown): string {
  if (e instanceof ApiError && e.status === 404) return "The platform no longer has this file.";
  return "Couldn't load the file. Check your connection and try again.";
}

/** Fetches the frozen bytes once and hands back an object URL, revoked on unmount. */
function useObjectUrl(path: string, enabled: boolean, attempt: number) {
  const key = `${path}#${attempt}`;
  // The settled answer for one request key; anything older reads as still loading.
  const [settled, setSettled] = useState<{ key: string; url: string | null; error: string | null } | null>(null);
  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    let made: string | null = null;
    apiBlob(path)
      .then((blob) => {
        if (cancelled) return;
        made = URL.createObjectURL(blob);
        setSettled({ key, url: made, error: null });
      })
      .catch((e: unknown) => {
        if (!cancelled) setSettled({ key, url: null, error: errorText(e) });
      });
    return () => {
      cancelled = true;
      if (made) URL.revokeObjectURL(made);
    };
  }, [path, enabled, key]);
  const current = settled?.key === key ? settled : null;
  return { url: current?.url ?? null, error: current?.error ?? null, loading: enabled && !current };
}

/**
 * The frozen copy of a FILE result (DeliverySnapshot, #97). It is what the reader reviews and
 * downloads: the bytes come from the platform (`downloadPath`), never from the agent's link, which
 * is shown only as plain text saying where the copy was fetched from. Images get an inline preview,
 * drawn from a blob URL so nothing the agent sent runs on this origin.
 */
export function DeliverablePanel({
  deliverable,
  sourceUrl,
  downloadPath,
  fileStem,
}: {
  deliverable: DeliverableDTO;
  sourceUrl: string | null;
  /** Path for `apiBlob`, e.g. `/tasks/{id}/deliverable`. */
  downloadPath: string;
  /** Download name without extension. */
  fileStem: string;
}) {
  const previewable = PREVIEWABLE.has(deliverable.mediaType);
  const [attempt, setAttempt] = useState(0);
  const preview = useObjectUrl(downloadPath, previewable, attempt);
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  async function download() {
    setDownloading(true);
    setDownloadError(null);
    try {
      const blob = await apiBlob(downloadPath);
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `${fileStem}.${EXTENSION[deliverable.mediaType] ?? "bin"}`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (e) {
      setDownloadError(errorText(e));
    } finally {
      setDownloading(false);
    }
  }

  const declaredDiffers =
    deliverable.declaredMediaType && deliverable.declaredMediaType !== deliverable.mediaType;

  return (
    <section aria-labelledby="deliverable-h" className="docket overflow-hidden">
      <div className="flex flex-wrap items-start justify-between gap-3 px-5 pb-3 pt-5">
        <div className="max-w-xl">
          <h3 id="deliverable-h" className="eyebrow">
            Delivered file
          </h3>
          <p className="mt-1 text-sm text-muted">
            A frozen copy the platform took when the agent delivered. This is the file you review, and the one an
            arbitrator would see; changing or deleting the agent&apos;s link afterwards doesn&apos;t change it.
          </p>
        </div>
        <Button variant="secondary" size="sm" onClick={download} disabled={downloading}>
          {downloading ? "Preparing…" : "Download file"}
        </Button>
      </div>
      <div className="docket-tear" />

      {previewable && (
        <div className="border-b border-line bg-surface-2 px-5 py-4">
          {preview.loading && (
            <div role="status">
              <span className="sr-only">Loading the preview</span>
              <Skeleton className="h-48 w-full rounded-md" />
            </div>
          )}
          {preview.error && (
            <div role="alert" className="flex flex-wrap items-center gap-3 text-sm">
              <span className="text-red">{preview.error}</span>
              <Button variant="ghost" size="sm" onClick={() => setAttempt((n) => n + 1)}>
                Try again
              </Button>
            </div>
          )}
          {preview.url && (
            // eslint-disable-next-line @next/next/no-img-element -- a blob: URL, not an optimisable asset
            <img
              src={preview.url}
              alt="Preview of the delivered file"
              className="mx-auto max-h-96 max-w-full rounded-md border border-line bg-canvas object-contain"
            />
          )}
        </div>
      )}

      <dl className="grid grid-cols-1 gap-x-6 gap-y-3 px-5 py-4 text-sm sm:grid-cols-2">
        <div>
          <dt className="text-xs uppercase tracking-wide text-dim">Type</dt>
          <dd className="mt-0.5 text-fg">
            {typeLabel(deliverable.mediaType)}
            {declaredDiffers && (
              <span className="block text-xs text-amber">
                The agent labelled it {deliverable.declaredMediaType}; the platform judged the bytes.
              </span>
            )}
          </dd>
        </div>
        <div>
          <dt className="text-xs uppercase tracking-wide text-dim">Size</dt>
          <dd className="tabular mt-0.5 text-fg">{formatBytes(deliverable.sizeBytes)}</dd>
        </div>
        <div>
          <dt className="text-xs uppercase tracking-wide text-dim">Copy taken</dt>
          <dd className="mt-0.5 text-fg">
            <time dateTime={deliverable.fetchedAt}>{new Date(deliverable.fetchedAt).toLocaleString()}</time>
          </dd>
        </div>
        <div className="min-w-0">
          <dt className="text-xs uppercase tracking-wide text-dim">Fingerprint (SHA-256)</dt>
          <dd className="mt-0.5 break-all font-mono text-xs text-muted" title={deliverable.sha256}>
            {deliverable.sha256}
          </dd>
        </div>
      </dl>

      {(downloadError || sourceUrl) && (
        <div className="space-y-1 border-t border-line px-5 py-3 text-xs">
          {downloadError && (
            <p role="alert" className="text-red">
              {downloadError}
            </p>
          )}
          {sourceUrl && (
            <p className="break-all text-dim">
              Fetched from <span className="font-mono">{sourceUrl}</span>
            </p>
          )}
        </div>
      )}
    </section>
  );
}

/**
 * A FILE result with no frozen copy: delivered before the platform kept copies. The agent's link is
 * all there is, and it is labelled as such.
 */
export function LegacyDeliverableLink({ url }: { url: string }) {
  return (
    <p className="text-sm text-muted">
      This file was delivered before the platform kept copies, so it lives only at the agent&apos;s link, which the
      agent could change.{" "}
      <a
        href={url}
        target="_blank"
        rel="noreferrer noopener"
        className="font-medium text-accent underline-offset-4 hover:underline"
      >
        Open the agent&apos;s link ↗
      </a>
    </p>
  );
}
