"use client";

import { useState } from "react";
import { api, ApiError } from "@/lib/api";
import type { AgentDTO } from "@/lib/types";
import { Field } from "@/components/ui/Field";
import { Input } from "@/components/ui/Input";
import { Button } from "@/components/ui/Button";
import { CategoryPicker } from "@/components/CategoryPicker";
import { OfferPanel, useTemplates } from "@/components/AcceptanceTerms";
import {
  OfferEditor,
  offerDraftOf,
  offerDraftProblems,
  offerTermsFromDraft,
  ownContractOf,
  type OfferDraft,
} from "@/components/OfferEditor";
import { Skeleton } from "@/components/ui";
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
  const own = ownContractOf(ver.outputSpec);
  const [draft, setDraft] = useState<OfferDraft | null>(null);
  const [showProblems, setShowProblems] = useState(false);
  const [acceptsAiReview, setAcceptsAiReview] = useState(ver.offer?.acceptsAiReview ?? false);
  const templates = useTemplates(draft !== null);
  const templateList = templates.status === "ready" ? [...templates.byRef.values()] : [];
  const problems = draft && showProblems ? offerDraftProblems(draft, templateList) : {};

  function startEditing() {
    setDraft(offerDraftOf(ver.offer, own));
    setAcceptsAiReview(ver.offer?.acceptsAiReview ?? false);
    setShowProblems(false);
  }

  function stopEditing() {
    setDraft(null);
    setShowProblems(false);
    setError(null);
  }

  async function handleSave() {
    setError(null);
    if (draft) {
      if (templates.status !== "ready") {
        setError("The Templates haven't loaded, so the edited Offer can't be published. Keep the current Offer or try again.");
        return;
      }
      if (Object.keys(offerDraftProblems(draft, templateList)).length > 0) {
        setShowProblems(true);
        setError("Fix the offer terms marked above before publishing.");
        return;
      }
    }
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
          ...(draft ? { offer: { terms: offerTermsFromDraft(draft, templateList), acceptsAiReview } } : {}),
        }),
      });
      onAgentChange(updated);
      setDraft(null);
      setShowProblems(false);
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

      <section aria-labelledby="offer-h" className="docket overflow-hidden">
        <div className="px-5 pb-3 pt-5">
          <h3 id="offer-h" className="eyebrow">
            Offer
          </h3>
          <p className="mt-1 text-sm text-muted">
            The terms this version works under: the Templates it accepts and the range of each parameter it can be
            held to. Routed tasks reach this agent only when their terms fall inside it. It carries over to each new
            version unless you edit it here; the price is the one below.
          </p>
        </div>
        <div className="border-t border-line px-5 py-4">
          {draft === null ? (
            <div className="space-y-4">
              <OfferPanel offer={ver.offer} />
              <p className="text-sm text-muted">
                {ver.offer?.acceptsAiReview
                  ? "Accepts AI Review: clients may pay for a model to judge criteria code can't check. On the API its verdict settles the task and is final."
                  : "Doesn't accept AI Review: clients can't add one when they book this agent."}
              </p>
              <Button variant="secondary" size="sm" onClick={startEditing}>
                Edit offer
              </Button>
            </div>
          ) : (
            <div className="space-y-4">
              <p className="text-sm text-muted">
                Tick each Template this version accepts, then choose how far it can be held on each parameter. A
                parameter you leave unset is accepted only when a task leaves it unset too. The edited Offer is
                published with the new version.
              </p>
              {templates.status === "loading" && (
                <div role="status" className="space-y-2">
                  <span className="sr-only">Loading the Templates</span>
                  <Skeleton className="h-16 w-full" />
                  <Skeleton className="h-16 w-full" />
                </div>
              )}
              {templates.status === "error" && (
                <p role="alert" className="rounded-md border border-red/30 bg-red/10 px-3 py-2 text-sm text-red">
                  Couldn&apos;t load the Templates, so the Offer can&apos;t be edited right now. Keep the current Offer
                  and try again later.
                </p>
              )}
              {templates.status === "ready" && (
                <OfferEditor templates={templateList} value={draft} own={own} problems={problems} onChange={setDraft} />
              )}
              <label className="flex items-start gap-3 rounded-lg border border-line bg-surface px-4 py-3">
                <input
                  type="checkbox"
                  className="mt-1 size-4 accent-[var(--color-accent)]"
                  checked={acceptsAiReview}
                  onChange={(e) => setAcceptsAiReview(e.target.checked)}
                />
                <span>
                  <span className="block text-sm font-medium text-fg">Accept AI Review</span>
                  <span className="mt-0.5 block text-sm text-muted">
                    Clients may pay a small fee for a model to judge criteria code can&apos;t check. On the web it only
                    advises the client; on the API its verdict settles the task and is final, with no appeal.
                  </span>
                </span>
              </label>
              <Button variant="ghost" size="sm" onClick={stopEditing}>
                Keep the current offer
              </Button>
            </div>
          )}
        </div>
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
