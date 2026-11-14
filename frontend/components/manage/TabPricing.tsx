"use client";

import { useState } from "react";
import { api, ApiError } from "@/lib/api";
import type { AgentDTO } from "@/lib/types";
import { Field } from "@/components/ui/Field";
import { Input } from "@/components/ui/Input";
import { Button } from "@/components/ui/Button";
import { CategoryPicker } from "@/components/CategoryPicker";
import { ContractTerms } from "@/components/ContractTerms";
import { MAX_EXECUTION_HINT, MAX_EXECUTION_SECONDS } from "@/lib/agentLimits";

interface Props {
  agentId: string;
  agent: AgentDTO;
  onAgentChange: (a: AgentDTO) => void;
}

export function TabPricing({ agentId, agent, onAgentChange }: Props) {
  const ver = agent.currentVersion;
  const [price, setPrice] = useState(ver.price);
  const [maxExec, setMaxExec] = useState(ver.maxExecutionSeconds);
  const [categories, setCategories] = useState<string[]>(ver.capabilityCategories);
  const [suggestedCategory, setSuggestedCategory] = useState("");
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSave() {
    setError(null);
    if (!Number.isInteger(maxExec) || maxExec < 1) {
      setError("Max execution seconds must be at least 1");
      return;
    }
    if (maxExec > MAX_EXECUTION_SECONDS) {
      setError(`Max execution seconds must be at most ${MAX_EXECUTION_SECONDS} (24 hours)`);
      return;
    }
    if (categories.length === 0) {
      setError("Pick at least one category");
      return;
    }
    setSaving(true);
    try {
      const updated = await api<AgentDTO>(`/agents/${agentId}/versions`, {
        method: "POST",
        body: JSON.stringify({
          price,
          maxExecutionSeconds: maxExec,
          capabilityCategories: categories,
          ...(suggestedCategory.trim() ? { suggestedCategory: suggestedCategory.trim() } : {}),
        }),
      });
      onAgentChange(updated);
      setSaved(true);
      setTimeout(() => setSaved(false), 2000);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Publish failed");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="space-y-6">
      <section aria-labelledby="contract-terms-h" className="docket overflow-hidden">
        <div className="px-5 pb-3 pt-5">
          <h3 id="contract-terms-h" className="eyebrow">
            Output contract
          </h3>
          <p className="mt-1 text-sm text-muted">
            Fixed when the agent was registered. Every booking adopts it, and validation and disputes are
            judged against it, so it can&apos;t be changed on a published agent.
          </p>
        </div>
        <ContractTerms spec={ver.outputSpec} />
      </section>

      <p className="text-xs text-amber">
        Publishing creates a new version and retires the current one — in-flight tasks keep the
        version they were dispatched with.
      </p>

      <div className="grid grid-cols-2 gap-4">
        <Field
          label="Price (credits)"
          htmlFor="price"
          hint={`You receive ${(price * 0.85).toFixed(2)} cr per accepted task, after the 15% platform fee.`}
        >
          <Input
            id="price"
            aria-label="Price"
            type="number"
            min={0}
            value={price}
            onChange={(e) => setPrice(Number(e.target.value))}
          />
        </Field>
        <Field label="Max execution seconds" htmlFor="maxExec" hint={MAX_EXECUTION_HINT}>
          <Input
            id="maxExec"
            aria-label="Max execution seconds"
            type="number"
            min={1}
            max={MAX_EXECUTION_SECONDS}
            value={maxExec}
            onChange={(e) => setMaxExec(Number(e.target.value))}
          />
        </Field>
      </div>

      <CategoryPicker
        id="categories"
        value={categories}
        onChange={setCategories}
        suggestion={suggestedCategory}
        onSuggestionChange={setSuggestedCategory}
      />

      {error && (
        <p role="alert" className="text-xs text-red">
          {error}
        </p>
      )}

      <div className="flex items-center gap-4">
        <Button onClick={handleSave} disabled={saving}>
          {saving ? "Publishing…" : "Publish version"}
        </Button>
        {saved && (
          <p role="status" className="text-xs text-accent">
            Published
          </p>
        )}
      </div>
    </div>
  );
}
