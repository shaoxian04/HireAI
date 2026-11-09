"use client";

import { useEffect, useState, type FormEvent } from "react";
import { useParams, useRouter } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import { Button, Field, Input, SkeletonRows, Textarea } from "@/components/ui";
import { ContractTerms } from "@/components/ContractTerms";
import { errorFor, toFormError, useFocusOnFieldError, validateForm, type FormError } from "@/lib/formErrors";
import type { AgentProfileDTO, DirectBookRequest, TaskDTO } from "@/lib/types";
import { directBookSignature, useIdempotencyKey } from "@/lib/useIdempotencyKey";
import { PageHeader } from "@/components/PageHeader";

function BookingForm() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();

  const [profile, setProfile] = useState<AgentProfileDTO | null>(null);
  const [profileError, setProfileError] = useState<string | null>(null);

  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");

  const [error, setError] = useState<FormError | null>(null);
  // A field error clears the moment that field (or any other) is edited, so it never goes stale.
  const clearFieldError = () => setError((er) => (er?.field ? null : er));
  const [submitting, setSubmitting] = useState(false);
  useFocusOnFieldError(error);
  // Built here rather than in onSubmit so the idempotency signature is derived from the very
  // object that gets sent: retrying the same booking reuses one key (deduped server-side),
  // while an edited title or description starts a fresh one.
  const bookingBody: DirectBookRequest | null = profile
    ? { title, description, budget: profile.card.price, agentId: id }
    : null;
  const getIdempotencyKey = useIdempotencyKey(directBookSignature(bookingBody));

  useEffect(() => {
    if (!id) return;
    // Reset so navigating between agents never flashes a previous agent.
    // eslint-disable-next-line react-hooks/set-state-in-effect -- intentional reset-on-navigation so a new :id never flashes stale data
    setProfile(null);
    setProfileError(null);
    api<AgentProfileDTO>(`/catalogue/agents/${id}`)
      .then((p) => setProfile(p))
      .catch((e) =>
        setProfileError(e instanceof ApiError ? e.message : "Failed to load agent"),
      );
  }, [id]);

  if (profileError)
    return (
      <p
        role="alert"
        className="rounded-md border border-red/30 bg-red/10 px-3 py-2 text-xs text-red"
      >
        {profileError}
      </p>
    );
  if (!profile)
    return <SkeletonRows label="Loading agent profile…" rows={3} />;

  const { card } = profile;

  const bookErrors = { title: "Title", description: "Description" };

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    const invalid = validateForm(e.currentTarget as HTMLFormElement);
    if (invalid) {
      setError(invalid);
      return;
    }
    setSubmitting(true);
    try {
      const created = await api<TaskDTO>("/tasks/direct", {
        method: "POST",
        headers: { "Idempotency-Key": getIdempotencyKey() },
        body: JSON.stringify(bookingBody),
      });
      router.push(`/client/tasks/${created.id}`);
    } catch (err) {
      setError(toFormError(err, bookErrors, "Booking failed. Check your connection and try again."));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="mx-auto max-w-xl space-y-6">
      <PageHeader
        back={{ href: `/client/agents/${id}`, label: "Agent details" }}
        title={`Book ${card.name}`}
        description="Booking adopts this agent's output contract. Its price freezes in escrow when you submit, and comes back to you if the work fails it."
      />

      <div className="docket overflow-hidden">
        <section aria-labelledby="contract-h">
          <div className="px-5 pb-3 pt-5">
            <h2 id="contract-h" className="eyebrow">
              The contract you&apos;re adopting
            </h2>
            <p className="mt-1 text-sm text-muted">
              The agent&apos;s result is validated against these terms before you see it.
            </p>
          </div>
          <ContractTerms spec={profile.outputSpec} />
        </section>

        <div className="docket-tear" />

        <form onSubmit={onSubmit} noValidate className="space-y-4 bg-surface-2 p-5 sm:p-6">
          <h2 className="eyebrow">Your brief</h2>
          <Field label="Title" htmlFor="title" error={errorFor(error, "title")}>
            <Input
              id="title"
              value={title}
              onChange={(e) => { setTitle(e.target.value); clearFieldError(); }}
              placeholder="e.g. Summarise the Q2 board report"
              required
            />
          </Field>
          <Field
            label="Description"
            htmlFor="description"
            error={errorFor(error, "description")}
            hint="What the agent should work on and anything it must know. It is sent to the agent as written."
          >
            <Textarea
              id="description"
              value={description}
              onChange={(e) => { setDescription(e.target.value); clearFieldError(); }}
              rows={5}
              required
            />
          </Field>

          <dl className="grid grid-cols-2 gap-px overflow-hidden rounded-lg border border-line bg-line text-sm">
            <div className="bg-surface px-4 py-3">
              <dt className="text-xs text-dim">You&apos;ll pay</dt>
              <dd className="display tabular mt-0.5 text-2xl text-accent">{card.price} cr</dd>
            </div>
            <div className="bg-surface px-4 py-3">
              <dt className="text-xs text-dim">Agent must deliver within</dt>
              <dd className="display tabular mt-0.5 text-2xl">{card.maxExecutionSeconds}s</dd>
            </div>
          </dl>
          <p className="text-xs text-dim">
            The price is fixed by the agent and frozen in escrow, not charged, until you accept the result.
          </p>

          {error && !error.field && (
            <p
              role="alert"
              className="rounded-md border border-red/30 bg-red/10 px-3 py-2 text-sm text-red"
            >
              {error.message}
            </p>
          )}
          <Button type="submit" disabled={submitting} className="w-full">
            {submitting ? "Booking…" : `Book and freeze ${card.price} cr`}
          </Button>
        </form>
      </div>
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
