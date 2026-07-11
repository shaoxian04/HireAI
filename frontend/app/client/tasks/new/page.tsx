"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import { ShortlistPanel } from "@/components/ShortlistPanel";
import type { AgentOptionDTO, DirectBookRequest, MatchPreviewDTO, TaskDTO } from "@/lib/types";
import { Button, Card, Field, Input } from "@/components/ui";

const DRAFT_KEY = "hireai.taskDraft";

interface Draft {
  title: string;
  description: string;
  category: string;
  budget: number;
}

function SubmitTask() {
  const router = useRouter();
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [category, setCategory] = useState("");
  const [budget, setBudget] = useState(30);
  const [preview, setPreview] = useState<MatchPreviewDTO | null>(null);
  const [selected, setSelected] = useState<AgentOptionDTO | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const firstPersist = useRef(true);

  // Restore the draft once on mount so a reload / re-search never loses the client's work.
  useEffect(() => {
    const raw = typeof localStorage !== "undefined" ? localStorage.getItem(DRAFT_KEY) : null;
    if (!raw) return;
    try {
      const d = JSON.parse(raw) as Draft;
      setTitle(d.title ?? "");
      setDescription(d.description ?? "");
      setCategory(d.category ?? "");
      setBudget(typeof d.budget === "number" ? d.budget : 30);
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
    setLoading(true);
    try {
      const result = await api<MatchPreviewDTO>(
        `/tasks/match-preview?category=${encodeURIComponent(category)}&budget=${budget}`,
      );
      setPreview(result);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Search failed");
    } finally {
      setLoading(false);
    }
  }

  async function onBook() {
    if (!selected) return;
    setError(null);
    setLoading(true);
    const body: DirectBookRequest = {
      title,
      description,
      budget: selected.price, // pay the chosen agent's price
      agentId: selected.agentId,
    };
    try {
      const created = await api<TaskDTO>("/tasks/direct", {
        method: "POST",
        body: JSON.stringify(body),
      });
      if (typeof localStorage !== "undefined") localStorage.removeItem(DRAFT_KEY);
      router.push(`/client/tasks/${created.id}`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Booking failed");
      setLoading(false);
    }
  }

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
            <Input
              id="category"
              value={category}
              onChange={(e) => setCategory(e.target.value)}
              placeholder="must match an active agent's category"
              required
            />
          </Field>
          <Field label="Budget (credits)" htmlFor="budget">
            <Input
              id="budget"
              type="number"
              min={0}
              value={budget}
              onChange={(e) => setBudget(Number(e.target.value))}
              required
            />
          </Field>
          <Button type="submit" disabled={loading} className="w-full">
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

      {preview && !selected && (
        <ShortlistPanel
          shortlist={preview.shortlist}
          nearMisses={preview.nearMisses}
          budget={budget}
          onSelect={setSelected}
        />
      )}

      {selected && (
        <Card>
          <p className="eyebrow mb-2">Confirm booking</p>
          <p className="text-sm text-muted">
            You&apos;ll pay{" "}
            <span className="text-accent">{selected.price} cr</span> to {selected.agentName},
            frozen in escrow.
            {selected.price > budget && (
              <> This is above your {budget} cr budget.</>
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
