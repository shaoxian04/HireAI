"use client";

import { useState } from "react";
import { api, ApiError } from "@/lib/api";
import type { AgentProfileViewDTO } from "@/lib/types";
import { Field } from "@/components/ui/Field";
import { Input } from "@/components/ui/Input";
import { Button } from "@/components/ui/Button";
import { MediaUploader } from "@/components/MediaUploader";

interface Props {
  agentId: string;
  profile: AgentProfileViewDTO;
  onProfileChange: (p: AgentProfileViewDTO) => void;
}

export function TabStorefront({ agentId, profile, onProfileChange }: Props) {
  const [tagline, setTagline] = useState(profile.tagline ?? "");
  const [description, setDescription] = useState(profile.description ?? "");
  const [sampleOutput, setSampleOutput] = useState(profile.sampleOutput ?? "");
  const [listed, setListed] = useState(profile.listed);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSave() {
    setSaving(true);
    setError(null);
    try {
      const updated = await api<AgentProfileViewDTO>(`/agents/${agentId}/profile`, {
        method: "PUT",
        body: JSON.stringify({
          tagline: tagline || null,
          description: description || null,
          sampleOutput: sampleOutput || null,
          isListed: listed,
        }),
      });
      onProfileChange(updated);
      setSaved(true);
      setTimeout(() => setSaved(false), 2000);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Save failed");
    } finally {
      setSaving(false);
    }
  }

  async function removeGalleryImage(url: string) {
    try {
      const updated = await api<AgentProfileViewDTO>(
        `/agents/${agentId}/media?kind=gallery&url=${encodeURIComponent(url)}`,
        { method: "DELETE" },
      );
      onProfileChange(updated);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Remove failed");
    }
  }

  return (
    <div className="space-y-6">
      <Field label="Tagline" htmlFor="tagline">
        <Input
          id="tagline"
          value={tagline}
          onChange={(e) => setTagline(e.target.value)}
          placeholder="One-line pitch"
        />
      </Field>

      <Field label="Description" htmlFor="description">
        <textarea
          id="description"
          rows={5}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder="What this agent does and who it's for"
          className="block w-full rounded-md border border-line bg-surface-2 px-3 py-2 font-mono text-sm text-fg shadow-inner transition placeholder:text-dim focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/25 disabled:opacity-50"
        />
      </Field>

      <Field label="Sample output" htmlFor="sampleOutput">
        <textarea
          id="sampleOutput"
          rows={4}
          value={sampleOutput}
          onChange={(e) => setSampleOutput(e.target.value)}
          placeholder='{"example": "output"}'
          className="block w-full rounded-md border border-line bg-surface-2 px-3 py-2 font-mono text-sm text-fg shadow-inner transition placeholder:text-dim focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/25 disabled:opacity-50 font-mono"
        />
      </Field>

      <div className="flex flex-wrap gap-6">
        <MediaUploader
          agentId={agentId}
          kind="logo"
          label="Logo"
          currentUrl={profile.logoUrl}
          onUploaded={onProfileChange}
        />
        <MediaUploader
          agentId={agentId}
          kind="cover"
          label="Cover image"
          currentUrl={profile.coverUrl}
          onUploaded={onProfileChange}
        />
      </div>

      {/* Gallery */}
      <div className="space-y-3">
        <p className="font-mono text-[0.7rem] uppercase tracking-[0.18em] text-muted">Gallery</p>
        {profile.galleryUrls.length > 0 && (
          <div className="flex flex-wrap gap-3">
            {profile.galleryUrls.map((url) => (
              <div key={url} className="relative">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img
                  src={url}
                  alt="Gallery image"
                  className="h-20 w-20 rounded-md border border-line object-cover"
                />
                <Button
                  type="button"
                  variant="ghost"
                  className="absolute -right-1 -top-1 !px-1 !py-0.5 text-[0.6rem]"
                  onClick={() => removeGalleryImage(url)}
                >
                  ×
                </Button>
              </div>
            ))}
          </div>
        )}
        <MediaUploader
          agentId={agentId}
          kind="gallery"
          label="Gallery image"
          onUploaded={onProfileChange}
        />
      </div>

      {/* Listed toggle */}
      <label className="flex cursor-pointer items-center gap-2 font-mono text-xs text-muted">
        <input
          type="checkbox"
          checked={listed}
          onChange={(e) => setListed(e.target.checked)}
          className="rounded border-line bg-surface-2 accent-accent"
        />
        Listed on marketplace
      </label>

      {error && (
        <p role="alert" className="font-mono text-xs text-red">
          {error}
        </p>
      )}

      <div className="flex items-center gap-4">
        <Button onClick={handleSave} disabled={saving}>
          {saving ? "Saving…" : "Save ▸"}
        </Button>
        {saved && (
          <p role="status" className="font-mono text-xs text-accent">
            Saved
          </p>
        )}
      </div>
    </div>
  );
}
