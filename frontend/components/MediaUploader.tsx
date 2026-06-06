"use client";

import { useRef, useState } from "react";
import { apiUpload, ApiError } from "@/lib/api";
import type { AgentProfileViewDTO, MediaKind } from "@/lib/types";
import { Button } from "@/components/ui/Button";

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
      <p className="font-mono text-[0.7rem] uppercase tracking-[0.18em] text-muted">{label}</p>
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
      <Button
        type="button"
        variant="secondary"
        disabled={uploading}
        onClick={() => inputRef.current?.click()}
      >
        {uploading ? "Uploading…" : currentUrl ? "Replace ▸" : "Upload ▸"}
      </Button>
      {error && (
        <p role="alert" className="font-mono text-xs text-red">
          {error}
        </p>
      )}
    </div>
  );
}
