"use client";

import { useState } from "react";

/** `summary_text` / `summaryText` → "Summary text". */
function humanizeKey(key: string): string {
  const words = key
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .replace(/[_-]+/g, " ")
    .trim()
    .toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

function parse(raw: string): { ok: true; value: unknown } | { ok: false } {
  try {
    return { ok: true, value: JSON.parse(raw) };
  } catch {
    return { ok: false };
  }
}

function isPrimitive(v: unknown): v is string | number | boolean | null {
  return v === null || ["string", "number", "boolean"].includes(typeof v);
}

/** One value, rendered for reading: prose for text, a list for flat arrays, JSON for the rest. */
function Value({ value }: { value: unknown }) {
  if (typeof value === "string") {
    return <p className="whitespace-pre-wrap break-words text-[0.9375rem] leading-relaxed text-fg">{value}</p>;
  }
  if (typeof value === "number" || typeof value === "boolean" || value === null) {
    return <p className="tabular font-mono text-sm text-fg">{String(value)}</p>;
  }
  if (Array.isArray(value) && value.every(isPrimitive)) {
    return (
      <ul className="list-disc space-y-1 pl-5 text-[0.9375rem] leading-relaxed text-fg marker:text-dim">
        {value.map((v, i) => (
          <li key={i} className="break-words">{String(v)}</li>
        ))}
      </ul>
    );
  }
  return (
    <pre className="whitespace-pre-wrap break-words rounded-md bg-canvas p-3 font-mono text-xs leading-relaxed text-muted">
      {JSON.stringify(value, null, 2)}
    </pre>
  );
}

/**
 * The agent's deliverable, rendered for a person to read rather than as a raw payload: a JSON
 * object becomes labelled fields (long text wraps as prose), plain text stays text. The exact
 * payload is always one click away under "Raw response", since that is what validation and
 * arbitration judge.
 */
export function ResultView({ payload }: { payload: string }) {
  const [copied, setCopied] = useState(false);
  const parsed = parse(payload);
  const pretty = parsed.ok ? JSON.stringify(parsed.value, null, 2) : payload;

  let body;
  if (!parsed.ok || isPrimitive(parsed.value) || Array.isArray(parsed.value)) {
    body = <Value value={parsed.ok ? parsed.value : payload} />;
  } else {
    const entries = Object.entries(parsed.value as Record<string, unknown>);
    body =
      entries.length === 0 ? (
        <p className="text-sm text-dim">The agent returned an empty object.</p>
      ) : (
        <dl className="space-y-4">
          {entries.map(([k, v]) => (
            <div key={k} className="space-y-1">
              <dt className="text-xs font-medium text-dim">{humanizeKey(k)}</dt>
              <dd>
                <Value value={v} />
              </dd>
            </div>
          ))}
        </dl>
      );
  }

  async function copy() {
    try {
      await navigator.clipboard.writeText(pretty);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      /* clipboard unavailable — nothing to do */
    }
  }

  return (
    <div className="rounded-lg border border-line bg-surface-2/50">
      <div className="p-4 sm:p-5">{body}</div>
      <details className="group border-t border-line">
        <summary className="flex cursor-pointer list-none items-center justify-between px-4 py-2.5 text-xs text-dim transition hover:text-fg sm:px-5">
          <span>
            <span className="mr-1.5 inline-block transition group-open:rotate-90">›</span>
            Raw response
          </span>
          <button
            type="button"
            onClick={(e) => {
              e.preventDefault();
              void copy();
            }}
            className="rounded px-1.5 py-0.5 text-xs text-dim transition hover:bg-surface hover:text-fg"
          >
            {copied ? "Copied" : "Copy"}
          </button>
        </summary>
        <pre className="max-h-96 overflow-auto whitespace-pre-wrap break-words border-t border-line bg-canvas px-4 py-3 font-mono text-xs leading-relaxed text-muted sm:px-5">
          {pretty}
        </pre>
      </details>
    </div>
  );
}
