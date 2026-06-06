"use client";

import { useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
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
      <header>
        <h1 className="text-2xl font-semibold tracking-tight text-slate-900">Submit task</h1>
        <p className="mt-1 text-sm text-slate-500">
          The budget is frozen in escrow until the work is accepted.
        </p>
      </header>
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
              className="block w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900 shadow-sm placeholder:text-slate-400 focus:border-slate-500 focus:outline-none focus:ring-1 focus:ring-slate-500"
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
            <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">
              {error}
            </p>
          )}
          <Button type="submit" disabled={submitting} className="w-full">
            {submitting ? "Submitting…" : "Submit"}
          </Button>
        </form>
      </Card>
    </div>
  );
}

export default function Page() {
  return (
    <RoleGuard role="CLIENT">
      <SubmitTask />
    </RoleGuard>
  );
}
