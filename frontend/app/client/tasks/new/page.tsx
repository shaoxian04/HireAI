"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import { ShortlistPanel } from "@/components/ShortlistPanel";
import { CategoryCombobox } from "@/components/CategoryCombobox";
import type { AgentOptionDTO, DirectBookRequest, MatchPreviewDTO, TaskDTO } from "@/lib/types";
import { directBookSignature, useIdempotencyKey } from "@/lib/useIdempotencyKey";
import { Button, Card, Field, Input } from "@/components/ui";

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
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
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
    const raw = typeof localStorage !== "undefined" ? localStorage.getItem(DRAFT_KEY) : null;
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
    setSelected(null);
    const budgetNum = budget.trim() === "" ? NaN : Number(budget);
    if (Number.isNaN(budgetNum)) {
      setError("Enter a budget");
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
      setError(err instanceof ApiError ? err.message : "Search failed");
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
      if (typeof localStorage !== "undefined") localStorage.removeItem(DRAFT_KEY);
      router.push(`/client/tasks/${created.id}`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Booking failed");
      setLoading(false);
    }
  }

  const budgetValue = budget.trim() === "" ? 0 : Number(budget);

  return (
    <div className="mx-auto max-w-xl space-y-6">
      <div>
        <Link href="/client" className="font-mono text-xs text-dim transition hover:text-accent">
          ← console
        </Link>
        <p className="eyebrow mt-4 flex items-center gap-2">
          <span className="inline-block h-px w-6 bg-accent" />
          New task
        </p>
        <h1 className="mt-3 text-3xl font-extrabold tracking-tight">Submit task</h1>
        <p className="mt-2 text-sm text-muted">
          Describe the task and find matching agents. Nothing is charged until you pick one — then
          that agent&apos;s price is frozen in escrow.
        </p>
      </div>

      <Card>
        <form onSubmit={onFind} className="space-y-4">
          <Field label="Title" htmlFor="title">
            <Input id="title" value={title} onChange={(e) => setTitle(e.target.value)} required />
          </Field>
          <Field label="Description" htmlFor="description">
            <textarea
              id="description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={4}
              className="block w-full rounded-md border border-line bg-surface-2 px-3 py-2 font-mono text-sm text-fg shadow-inner transition placeholder:text-dim focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/25"
              required
            />
          </Field>
          <Field label="Category" htmlFor="category">
            <CategoryCombobox id="category" value={category} onChange={setCategory} />
          </Field>
          <Field label="Budget (credits)" htmlFor="budget">
            <Input
              id="budget"
              type="number"
              min={0}
              value={budget}
              onChange={(e) => setBudget(e.target.value)}
              placeholder="30"
            />
          </Field>
          <Button type="submit" disabled={loading || !category} className="w-full">
            {loading ? "Searching…" : "Find agents ▸"}
          </Button>
        </form>
      </Card>

      {error && (
        <p
          role="alert"
          className="rounded-md border border-red/30 bg-red/10 px-3 py-2 font-mono text-xs text-red"
        >
          {error}
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
        <Button variant="secondary" onClick={() => setPreviewOpen(true)} className="w-full">
          Show {preview.shortlist.length + preview.nearMisses.length} matched agents ▸
        </Button>
      )}

      {selected && (
        <Card>
          <p className="eyebrow mb-2">Confirm booking</p>
          <p className="text-sm text-muted">
            You&apos;ll pay{" "}
            <span className="text-accent">{selected.price} cr</span> to {selected.agentName},
            frozen in escrow.
            {selected.price > budgetValue && (
              <> This is above your {budgetValue} cr budget.</>
            )}
          </p>
          <div className="mt-4 flex items-center gap-4">
            <Button onClick={onBook} disabled={loading}>
              {loading ? "Booking…" : "Confirm & book ▸"}
            </Button>
            <button
              type="button"
              onClick={() => setSelected(null)}
              className="font-mono text-xs text-dim transition hover:text-accent"
            >
              ← back
            </button>
          </div>
        </Card>
      )}
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
