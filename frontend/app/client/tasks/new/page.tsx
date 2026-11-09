"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { errorFor, toFormError, useFocusOnFieldError, validateForm, type FormError } from "@/lib/formErrors";

const TASK_FIELDS = { title: "Title", description: "Description", budget: "Budget" };
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import { ShortlistPanel } from "@/components/ShortlistPanel";
import { CategoryCombobox } from "@/components/CategoryCombobox";
import type {
  AgentOptionDTO,
  DirectBookRequest,
  MatchPreviewDTO,
  TaskDTO,
  WalletDTO,
} from "@/lib/types";
import {
  directBookSignature,
  useIdempotencyKey,
} from "@/lib/useIdempotencyKey";
import { Button, Card, Field, Input, Textarea } from "@/components/ui";
import { PageHeader } from "@/components/PageHeader";

const DRAFT_KEY = "hireai.taskDraft";

interface Draft {
  title: string;
  description: string;
  category: string;
  budget: string;
}

function SubmitTask() {
  const router = useRouter();
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [category, setCategory] = useState("");
  const [budget, setBudget] = useState("30");
  const [preview, setPreview] = useState<MatchPreviewDTO | null>(null);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [selected, setSelected] = useState<AgentOptionDTO | null>(null);
  const [error, setError] = useState<FormError | null>(null);
  const [available, setAvailable] = useState<number | null>(null);
  // A field error clears the moment that field (or any other) is edited, so it never goes stale.
  const clearFieldError = () => setError((er) => (er?.field ? null : er));
  const [loading, setLoading] = useState(false);
  useFocusOnFieldError(error);
  const firstPersist = useRef(true);
  // Built here rather than in onBook so the idempotency signature is derived from the very
  // object that gets sent: a retry of the *same* booking reuses one key (deduped server-side),
  // while editing a field or picking another agent starts a fresh one.
  const bookingBody: DirectBookRequest | null = selected
    ? { title, description, budget: selected.price, agentId: selected.agentId } // the agent's price, not the typed budget
    : null;
  const getIdempotencyKey = useIdempotencyKey(directBookSignature(bookingBody));

  // Restore the draft once on mount so a reload / re-search never loses the client's work.
  useEffect(() => {
    api<WalletDTO>("/wallet")
      .then((w) => setAvailable(w.availableBalance))
      .catch(() => {
        /* the preview is a nicety; the form works without it */
      });
  }, []);

  useEffect(() => {
    const raw =
      typeof localStorage !== "undefined"
        ? localStorage.getItem(DRAFT_KEY)
        : null;
    if (!raw) return;
    try {
      const d = JSON.parse(raw) as Draft;
      // eslint-disable-next-line react-hooks/set-state-in-effect -- SSR-safe client-only hydration from localStorage; lazy useState would read localStorage during SSR and cause hydration mismatch
      setTitle(d.title ?? "");
      setDescription(d.description ?? "");
      setCategory(d.category ?? "");
      setBudget(
        typeof d.budget === "number"
          ? String(d.budget)
          : typeof d.budget === "string"
            ? d.budget
            : "30",
      );
    } catch {
      /* ignore a malformed draft */
    }
  }, []);

  // Persist the draft whenever a field changes. Skip the first run: it fires on mount
  // alongside the restore effect above, and would otherwise overwrite the just-restored
  // draft with the blank initial state before it commits.
  useEffect(() => {
    if (typeof localStorage === "undefined") return;
    if (firstPersist.current) {
      firstPersist.current = false;
      return;
    }
    const draft: Draft = { title, description, category, budget };
    localStorage.setItem(DRAFT_KEY, JSON.stringify(draft));
  }, [title, description, category, budget]);

  async function onFind(e: FormEvent) {
    e.preventDefault();
    setError(null);
    const invalid = validateForm(e.currentTarget as HTMLFormElement);
    if (invalid) {
      setError(invalid);
      return;
    }
    setSelected(null);
    const budgetNum = budget.trim() === "" ? NaN : Number(budget);
    if (Number.isNaN(budgetNum)) {
      setError({ field: "budget", message: "Enter a budget in credits" });
      return;
    }
    setLoading(true);
    try {
      const result = await api<MatchPreviewDTO>(
        `/tasks/match-preview?category=${encodeURIComponent(category)}&budget=${budgetNum}`,
      );
      setPreview(result);
      setPreviewOpen(true);
    } catch (err) {
      setError(toFormError(err, TASK_FIELDS, "Couldn't search for agents. Check your connection and try again."));
    } finally {
      setLoading(false);
    }
  }

  async function onBook() {
    if (!bookingBody) return;
    setError(null);
    setLoading(true);
    try {
      const created = await api<TaskDTO>("/tasks/direct", {
        method: "POST",
        headers: { "Idempotency-Key": getIdempotencyKey() },
        body: JSON.stringify(bookingBody),
      });
      if (typeof localStorage !== "undefined")
        localStorage.removeItem(DRAFT_KEY);
      router.push(`/client/tasks/${created.id}`);
    } catch (err) {
      setError(toFormError(err, TASK_FIELDS, "Booking failed. Check your connection and try again."));
      setLoading(false);
    }
  }

  const budgetValue = budget.trim() === "" ? 0 : Number(budget);

  return (
    <div className="mx-auto max-w-xl space-y-6">
      <PageHeader
        back={{ href: "/client/tasks", label: "My tasks" }}
        title="Submit a task"
        description="Describe the task and find matching agents. Nothing is charged until you pick one; then that agent's price is frozen in escrow."
      />

      <div className="docket overflow-hidden">
        <form onSubmit={onFind} noValidate>
          <div className="space-y-4 p-6">
            <p className="font-mono text-xs text-dim">New work order</p>
            <Field label="Title" htmlFor="title" error={errorFor(error, "title")}>
              <Input
                id="title"
                value={title}
                onChange={(e) => { setTitle(e.target.value); clearFieldError(); }}
                required
              />
            </Field>
            <Field
              label="Description"
              htmlFor="description"
              error={errorFor(error, "description")}
              hint="What the agent should work on and anything it must know. It is sent as written."
            >
              <Textarea
                id="description"
                value={description}
                onChange={(e) => { setDescription(e.target.value); clearFieldError(); }}
                rows={5}
                required
              />
            </Field>
            <Field
              label="Category"
              htmlFor="category"
              hint={category ? undefined : "Choose one from the list. Agents are matched by the category they serve."}
            >
              <CategoryCombobox
                id="category"
                value={category}
                onChange={(v) => { setCategory(v); clearFieldError(); }}
              />
            </Field>
          </div>
          <div className="docket-tear" />
          <div className="space-y-4 bg-surface-2 p-6">
            <Field
              label="Budget (credits)"
              htmlFor="budget"
              error={errorFor(error, "budget")}
              hint="Agents within this budget are ranked first; a few just above it are shown as near-misses. You pay the price of the agent you book."
            >
              <Input
                id="budget"
                type="number"
                min={0}
                value={budget}
                onChange={(e) => { setBudget(e.target.value); clearFieldError(); }}
                placeholder="30"
              />
            </Field>
            {available !== null && <EscrowPreview available={available} budget={budgetValue} />}
            <Button
              type="submit"
              disabled={loading || !category}
              className="w-full"
            >
              {loading ? "Searching…" : "Find agents"}
            </Button>
          </div>
        </form>
      </div>

      {error && !error.field && (
        <p
          role="alert"
          className="rounded-md border border-red/30 bg-red/10 px-3 py-2 text-xs text-red"
        >
          {error.message}
        </p>
      )}

      {preview && (
        <ShortlistPanel
          open={previewOpen && !selected}
          shortlist={preview.shortlist}
          nearMisses={preview.nearMisses}
          budget={budgetValue}
          onSelect={(o) => {
            setSelected(o);
            setPreviewOpen(false);
          }}
          onClose={() => setPreviewOpen(false)}
        />
      )}

      {preview && !selected && !previewOpen && (
        <Button
          variant="secondary"
          onClick={() => setPreviewOpen(true)}
          className="w-full"
        >
          Show {preview.shortlist.length + preview.nearMisses.length} matched
          agents
        </Button>
      )}

      {selected && (
        <Card>
          <p className="eyebrow mb-2">Confirm booking</p>
          <p className="text-sm text-muted">
            You&apos;ll pay{" "}
            <span className="font-semibold text-accent">
              {selected.price} cr
            </span>{" "}
            to {selected.agentName}, frozen in escrow.
            {selected.price > budgetValue && (
              <> This is above your {budgetValue} cr budget.</>
            )}
          </p>
          <div className="mt-4 flex items-center gap-4">
            <Button onClick={onBook} disabled={loading}>
              {loading ? "Booking…" : "Confirm & book"}
            </Button>
            <button
              type="button"
              onClick={() => setSelected(null)}
              className="text-sm text-dim transition hover:text-fg"
            >
              ← Back
            </button>
          </div>
        </Card>
      )}
    </div>
  );
}

/**
 * What submitting would do to the client's money, as they set the budget: the wallet as a bar,
 * the share the budget would freeze, and a warning when the budget is more than they have.
 */
function EscrowPreview({ available, budget }: { available: number; budget: number }) {
  const over = budget > available;
  const share = available > 0 ? Math.min(100, (budget / available) * 100) : 100;
  return (
    <div className="space-y-2 rounded-lg border border-line bg-surface px-4 py-3">
      <div className="flex items-baseline justify-between gap-3 text-sm">
        <span className="text-muted">In your wallet</span>
        <span className="tabular font-semibold text-fg">{available} cr</span>
      </div>
      <div aria-hidden className="flex h-2 overflow-hidden rounded-full bg-line">
        <div className={over ? "bg-red" : "bg-accent"} style={{ width: `${share}%` }} />
      </div>
      <p className={`text-xs ${over ? "font-medium text-red" : "text-dim"}`}>
        {over
          ? `A budget of ${budget} cr is more than you have available. Top up from My tasks, or lower the budget.`
          : `Nothing is frozen yet. When you book, the agent's price (up to about ${budget} cr) moves from your wallet into escrow.`}
      </p>
    </div>
  );
}

export default function Page() {
  return (
    <AppShell>
      <RoleGuard role="CLIENT">
        <SubmitTask />
      </RoleGuard>
    </AppShell>
  );
}
