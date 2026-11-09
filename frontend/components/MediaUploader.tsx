"use client";

import { useRef, useState } from "react";
import { apiUpload, ApiError } from "@/lib/api";
import type { AgentProfileViewDTO, MediaKind } from "@/lib/types";

interface MediaUploaderProps {
  agentId: string;
  kind: MediaKind;
  label: string;
  currentUrl?: string | null;
  onUploaded: (profile: AgentProfileViewDTO) => void;
}

export function MediaUploader({ agentId, kind, label, currentUrl, onUploaded }: MediaUploaderProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;

    if (file.size > 2 * 1024 * 1024) {
      setError("Image must be ≤ 2 MB");
      return;
    }

    setError(null);
    setUploading(true);
    try {
      const form = new FormData();
      form.append("kind", kind);
      form.append("file", file);
      const profile = await apiUpload<AgentProfileViewDTO>(`/agents/${agentId}/media`, form);
      onUploaded(profile);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Upload failed");
    } finally {
      setUploading(false);
      // Reset so the same file can be re-selected after an error
      if (inputRef.current) inputRef.current.value = "";
    }
  }

  return (
    <div className="space-y-2">
      <p className="text-sm font-medium text-fg">{label}</p>
      {currentUrl && (
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src={currentUrl}
          alt={`${label} thumbnail`}
          className="h-20 w-20 rounded-md border border-line object-cover"
        />
      )}
      <input
        ref={inputRef}
        type="file"
        accept="image/png,image/jpeg,image/webp"
        aria-label={`${label} file`}
        className="sr-only"
        onChange={handleChange}
        disabled={uploading}
      />
      <button
        type="button"
        disabled={uploading}
        onClick={() => inputRef.current?.click()}
        className="flex w-full items-center gap-3 rounded-lg border-2 border-dashed border-line-bright bg-surface px-4 py-3 text-left transition hover:border-accent/60 hover:bg-accent/[0.03] disabled:cursor-wait disabled:opacity-60"
      >
        <svg viewBox="0 0 20 20" className="size-5 shrink-0 text-accent" aria-hidden>
          <path d="M10 13V4m0 0L6.5 7.5M10 4l3.5 3.5M4 13v2.5h12V13" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
        <span className="text-sm">
          <span className="font-semibold text-fg">{uploading ? "Uploading…" : currentUrl ? "Replace image" : "Upload image"}</span>
          <span className="block text-xs text-dim">PNG, JPEG or WebP</span>
        </span>
      </button>
      {error && (
        <p role="alert" className="text-xs text-red">
          {error}
        </p>
      )}
    </div>
  );
}
