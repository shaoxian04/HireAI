"use client";

import { useState, type FormEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import { EMPTY_OUTPUT_SPEC, OutputSpecFields } from "@/lib/outputSpecFields";
import type { CreateTaskRequest, OutputSpecDTO, TaskDTO } from "@/lib/types";
import { Button, Card, Field, Input } from "@/components/ui";

function SubmitTask() {
  const router = useRouter();
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [category, setCategory] = useState("");
  const [budget, setBudget] = useState(30);
  const [outputSpec, setOutputSpec] = useState<OutputSpecDTO>(EMPTY_OUTPUT_SPEC);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    const body: CreateTaskRequest = { title, description, category, budget, outputSpec };
    try {
      const created = await api<TaskDTO>("/tasks", {
        method: "POST",
        body: JSON.stringify(body),
      });
      router.push(`/client/tasks/${created.id}`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Submit failed");
    } finally {
      setSubmitting(false);
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
          The budget is frozen in escrow on submit and released only when the work is accepted.
        </p>
      </div>

      <Card>
        <form onSubmit={onSubmit} className="space-y-4">
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
          <OutputSpecFields value={outputSpec} onChange={setOutputSpec} />
          {error && (
            <p
              role="alert"
              className="rounded-md border border-red/30 bg-red/10 px-3 py-2 font-mono text-xs text-red"
            >
              {error}
            </p>
          )}
          <Button type="submit" disabled={submitting} className="w-full">
            {submitting ? "Submitting…" : "Submit & freeze escrow ▸"}
          </Button>
        </form>
      </Card>
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
