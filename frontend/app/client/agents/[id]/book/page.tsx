"use client";

import { useEffect, useState, type FormEvent } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import { Button, Card, Field, Input } from "@/components/ui";
import type { AgentProfileDTO, DirectBookRequest, TaskDTO } from "@/lib/types";

function BookingForm() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();

  const [profile, setProfile] = useState<AgentProfileDTO | null>(null);
  const [profileError, setProfileError] = useState<string | null>(null);

  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [budget, setBudget] = useState<number>(0);

  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!id) return;
    // Reset so navigating between agents never flashes a previous agent.
    // eslint-disable-next-line react-hooks/set-state-in-effect -- intentional reset-on-navigation so a new :id never flashes stale data
    setProfile(null);
    setProfileError(null);
    api<AgentProfileDTO>(`/catalogue/agents/${id}`)
      .then((p) => {
        setProfile(p);
        setBudget(p.card.price);
      })
      .catch((e) =>
        setProfileError(e instanceof ApiError ? e.message : "Failed to load agent"),
      );
  }, [id]);

  if (profileError)
    return (
      <p
        role="alert"
        className="rounded-md border border-red/30 bg-red/10 px-3 py-2 font-mono text-xs text-red"
      >
        {profileError}
      </p>
    );
  if (!profile)
    return <p className="font-mono text-sm text-dim">Loading agent profile…</p>;

  const { card } = profile;

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    const body: DirectBookRequest = { title, description, budget, agentId: id };
    try {
      const created = await api<TaskDTO>("/tasks/direct", {
        method: "POST",
        body: JSON.stringify(body),
      });
      router.push(`/client/tasks/${created.id}`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Booking failed");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="mx-auto max-w-xl space-y-6">
      <div>
        <Link
          href={`/client/agents/${id}`}
          className="font-mono text-xs text-dim transition hover:text-accent"
        >
          ← storefront
        </Link>
        <p className="eyebrow mt-4 flex items-center gap-2">
          <span className="inline-block h-px w-6 bg-accent" />
          Direct booking
        </p>
        <h1 className="mt-3 text-3xl font-extrabold tracking-tight">
          Book {card.name}
        </h1>
        <p className="mt-2 text-sm text-muted">
          You accept this agent&apos;s declared output contract; credits freeze in escrow on submit.
        </p>
      </div>

      {/* Adopted output contract — read-only */}
      <section>
        <p className="eyebrow mb-2">Output contract</p>
        <div className="rounded-md border border-line bg-surface-2 p-4 font-mono text-xs text-muted">
          <p>
            format ▸ <span className="text-cyan">{profile.outputSpec.format}</span>
          </p>
          {profile.outputSpec.schema && (
            <p className="mt-1">schema ▸ {profile.outputSpec.schema}</p>
          )}
          {profile.outputSpec.acceptanceCriteria && (
            <p className="mt-1">accepts ▸ {profile.outputSpec.acceptanceCriteria}</p>
          )}
        </div>
      </section>

      <Card>
        <form onSubmit={onSubmit} className="space-y-4" noValidate>
          <Field label="Title" htmlFor="title">
            <Input
              id="title"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              required
            />
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
          <Field label="Budget (credits)" htmlFor="budget">
            <Input
              id="budget"
              type="number"
              min={card.price}
              value={budget}
              onChange={(e) => setBudget(Number(e.target.value))}
              required
            />
          </Field>

          {/* Escrow summary row */}
          <p className="font-mono text-xs text-dim">
            Price{" "}
            <span className="tabular text-accent">{card.price} cr</span>
            {" "}· ≤{" "}
            <span className="tabular">{card.maxExecutionSeconds}s</span>{" "}
            execution · escrow-protected
          </p>

          {error && (
            <p
              role="alert"
              className="rounded-md border border-red/30 bg-red/10 px-3 py-2 font-mono text-xs text-red"
            >
              {error}
            </p>
          )}
          <Button type="submit" disabled={submitting} className="w-full">
            {submitting ? "Booking…" : "Book & freeze escrow ▸"}
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
        <BookingForm />
      </RoleGuard>
    </AppShell>
  );
}
