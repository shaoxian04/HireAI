"use client";

import { useState } from "react";
import { api, ApiError } from "@/lib/api";
import type { AgentDTO } from "@/lib/types";
import { Field } from "@/components/ui/Field";
import { Input } from "@/components/ui/Input";
import { Button } from "@/components/ui/Button";

interface Props {
  agentId: string;
  agent: AgentDTO;
  onAgentChange: (a: AgentDTO) => void;
}

export function TabPricing({ agentId, agent, onAgentChange }: Props) {
  const ver = agent.currentVersion;
  const [price, setPrice] = useState(ver.price);
  const [maxExec, setMaxExec] = useState(ver.maxExecutionSeconds);
  const [categoriesCsv, setCategoriesCsv] = useState(
    ver.capabilityCategories.join(", "),
  );
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSave() {
    setSaving(true);
    setError(null);
    try {
      const updated = await api<AgentDTO>(`/agents/${agentId}/pricing`, {
        method: "PUT",
        body: JSON.stringify({
          price,
          maxExecutionSeconds: maxExec,
          capabilityCategories: categoriesCsv
            .split(",")
            .map((s) => s.trim().toLowerCase())
            .filter(Boolean),
        }),
      });
      onAgentChange(updated);
      setSaved(true);
      setTimeout(() => setSaved(false), 2000);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Save failed");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="space-y-6">
      <p className="font-mono text-[0.65rem] text-amber">
        Edits apply to the live version immediately — no version history in this slice.
      </p>

      <div className="grid grid-cols-2 gap-4">
        <Field label="Price (credits)" htmlFor="price">
          <Input
            id="price"
            aria-label="Price"
            type="number"
            min={0}
            value={price}
            onChange={(e) => setPrice(Number(e.target.value))}
          />
        </Field>
        <Field label="Max execution seconds" htmlFor="maxExec">
          <Input
            id="maxExec"
            aria-label="Max execution seconds"
            type="number"
            min={1}
            value={maxExec}
            onChange={(e) => setMaxExec(Number(e.target.value))}
          />
        </Field>
      </div>

      <Field label="Capability categories (comma-separated)" htmlFor="categories">
        <Input
          id="categories"
          value={categoriesCsv}
          onChange={(e) => setCategoriesCsv(e.target.value)}
          placeholder="summarisation, translation"
        />
      </Field>

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
