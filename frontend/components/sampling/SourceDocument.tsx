"use client";

import { useEffect, useState } from "react";
import { ApiError, apiBlob } from "@/lib/api";
import { Button } from "@/components/ui";

const IMAGE = new Set(["image/png", "image/jpeg", "image/webp", "image/gif"]);
const TEXT = new Set(["text/plain", "text/csv", "application/json"]);

/**
 * An Item's source document (an Engagement's frozen SourceSnapshot), fetched from the platform on
 * demand for the reviewer to compare the agent's result against. Images and text show inline; any
 * other type opens from a blob URL, so nothing in the document runs on this origin.
 */
export function SourceDocument({ taskId, itemNo, label }: { taskId: string; itemNo: number; label: string }) {
  const [state, setState] = useState<
    | { kind: "idle" }
    | { kind: "loading" }
    | { kind: "error"; message: string }
    | { kind: "ready"; url: string; type: string; text?: string }
  >({ kind: "idle" });

  useEffect(() => () => {
    if (state.kind === "ready") URL.revokeObjectURL(state.url);
  }, [state]);

  async function open() {
    setState({ kind: "loading" });
    try {
      const blob = await apiBlob(`/tasks/${taskId}/items/${itemNo}/source`);
      const type = blob.type.split(";")[0];
      const text = TEXT.has(type) ? await blob.text() : undefined;
      setState({ kind: "ready", url: URL.createObjectURL(blob), type, text });
    } catch (e) {
      setState({
        kind: "error",
        message: e instanceof ApiError && e.status === 410
          ? "This document was deleted at the end of its retention period; only its hash is kept."
          : "Couldn't load the document. Check your connection and try again.",
      });
    }
  }

  if (state.kind === "idle" || state.kind === "loading") {
    return (
      <div className="flex flex-wrap items-center gap-3">
        <p className="min-w-0 break-words text-sm text-fg">{label}</p>
        <Button size="sm" variant="secondary" onClick={open} disabled={state.kind === "loading"}>
          {state.kind === "loading" ? "Opening…" : "Open the document"}
        </Button>
      </div>
    );
  }
  if (state.kind === "error") {
    return (
      <div className="space-y-2">
        <p role="alert" className="text-sm text-red">{state.message}</p>
        <Button size="sm" variant="secondary" onClick={open}>Try again</Button>
      </div>
    );
  }
  return (
    <div className="space-y-2">
      <p className="break-words text-xs text-dim">{label}</p>
      {IMAGE.has(state.type) ? (
        // eslint-disable-next-line @next/next/no-img-element -- a blob URL of the buyer's own document
        <img src={state.url} alt={`Source document of Item ${itemNo}`} className="max-h-96 w-full rounded-md border border-line object-contain" />
      ) : state.text != null ? (
        <pre className="max-h-96 overflow-auto whitespace-pre-wrap break-words rounded-md bg-canvas p-3 font-mono text-xs text-fg">
          {state.text}
        </pre>
      ) : (
        <a href={state.url} target="_blank" rel="noopener noreferrer"
          className="inline-flex text-sm font-medium text-accent underline-offset-4 hover:underline">
          Open in a new tab ↗
        </a>
      )}
    </div>
  );
}
