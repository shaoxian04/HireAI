"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
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
import { EngagementDocuments, type DocumentSelection } from "@/components/EngagementDocuments";
import { invoiceContract, invoiceTerm, offersBusinessValid, type InvoiceExpectation } from "@/lib/invoice";

/** A multi-Item Task holds two Items or more; a single piece of work is described in the brief. */
const MIN_ITEMS = 2;

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
  const [documents, setDocuments] = useState<DocumentSelection>({ engagementId: null, sourceIds: [] });
  const [stricter, setStricter] = useState(false);
  const [asInvoices, setAsInvoices] = useState(true);
  const [expectation, setExpectation] = useState<InvoiceExpectation>("faithful_to_source");
  const onDocumentsChange = useCallback((s: DocumentSelection) => setDocuments(s), []);
  useFocusOnFieldError(error);
  // Built here rather than in onSubmit so the idempotency signature is derived from the very
  // object that gets sent: retrying the same booking reuses one key (deduped server-side),
  // while an edited title or description starts a fresh one.
  const itemCount = documents.engagementId ? documents.sourceIds.length : 0;
  const strictAllowed = profile?.offer?.strictestInspectionLevel === "III";
  // Documents booked as invoices (#106) freeze the invoice Contract when the agent offers it.
  const invoice = itemCount > 0 && asInvoices && profile?.offer ? invoiceContract(profile.offer, expectation) : null;
  const bookingBody: DirectBookRequest | null = profile
    ? {
        title, description, budget: profile.card.price, agentId: id,
        // Documents become the Items, priced each at the agent's price, under the Engagement.
        ...(itemCount > 0 && documents.engagementId
          ? {
              engagementId: documents.engagementId,
              items: documents.sourceIds.map((sourceId) => ({ sourceId })),
              ...(stricter && strictAllowed ? { inspectionLevel: "III" as const } : {}),
              ...(invoice ? { contract: invoice } : {}),
            }
          : {}),
      }
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
  const total = itemCount > 0 ? Math.round(card.price * itemCount * 100) / 100 : card.price;

  const bookErrors = { title: "Title", description: "Description" };

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    const invalid = validateForm(e.currentTarget as HTMLFormElement);
    if (invalid) {
      setError(invalid);
      return;
    }
    if (itemCount > 0 && itemCount < MIN_ITEMS) {
      setError({ field: null, message: "Choose at least two documents: each document is one Item, and a batch holds two or more. "
        + "For a single piece of work, untick the document and describe it in the brief." });
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

          <EngagementDocuments agentId={id} agentName={card.name} onChange={onDocumentsChange} />

          {itemCount > 0 && invoiceTerm(profile.offer) && (
            <fieldset className="space-y-3 rounded-lg border border-line bg-surface px-4 py-3">
              <legend className="px-1 text-xs font-semibold text-muted">What each document is</legend>
              <label className="flex items-start gap-2 text-sm text-fg">
                <input type="checkbox" className="mt-1" checked={asInvoices} onChange={(e) => setAsInvoices(e.target.checked)} />
                <span>
                  <span className="font-medium">Supplier invoices.</span>{" "}
                  <span className="text-muted">
                    {"The agent extracts each invoice's fields. The platform checks they are all there and that the "
                      + "figures add up, and tells you about duplicates. A Finding never changes what the agent is paid."}
                  </span>
                </span>
              </label>
              {asInvoices && (
                <div className="space-y-2 border-t border-line pt-3">
                  <p className="text-xs font-semibold text-muted">When an invoice&apos;s figures don&apos;t add up</p>
                  <label className="flex items-start gap-2 text-sm text-fg">
                    <input type="radio" name="expectation" className="mt-1" checked={expectation === "faithful_to_source"}
                      onChange={() => setExpectation("faithful_to_source")} />
                    <span>
                      {"Copy the document as it is, and let me check it "}
                      <span className="text-muted">(recommended: the supplier may have got it wrong)</span>
                    </span>
                  </label>
                  {offersBusinessValid(profile.offer) ? (
                    <label className="flex items-start gap-2 text-sm text-fg">
                      <input type="radio" name="expectation" className="mt-1" checked={expectation === "business_valid"}
                        onChange={() => setExpectation("business_valid")} />
                      <span>
                        {"The figures must add up "}
                        <span className="text-muted">(the agent reports why when they can&apos;t; if it doesn&apos;t, the invoice is refunded)</span>
                      </span>
                    </label>
                  ) : (
                    <p className="text-xs text-dim">This agent works faithful to the document only.</p>
                  )}
                </div>
              )}
            </fieldset>
          )}

          {itemCount >= MIN_ITEMS && (
            <fieldset className="space-y-2 rounded-lg border border-line bg-surface px-4 py-3">
              <legend className="px-1 text-xs font-semibold text-muted">How the batch is checked</legend>
              <p className="text-sm text-muted">
                You check a random sample of the documents against the agent&apos;s results, sized by a standard
                lot-size table, and the sample decides the rest.
              </p>
              {strictAllowed ? (
                <label className="flex items-start gap-2 text-sm text-fg">
                  <input type="checkbox" className="mt-1" checked={stricter} onChange={(e) => setStricter(e.target.checked)} />
                  <span>Inspect more strictly (level III): a larger sample, which this agent accepts.</span>
                </label>
              ) : (
                <p className="text-xs text-dim">This agent accepts the normal inspection level (II) only.</p>
              )}
            </fieldset>
          )}

          <dl className="grid grid-cols-2 gap-px overflow-hidden rounded-lg border border-line bg-line text-sm">
            <div className="bg-surface px-4 py-3">
              <dt className="text-xs text-dim">You&apos;ll pay</dt>
              <dd className="display tabular mt-0.5 text-2xl text-accent">{total} cr</dd>
              {itemCount > 0 && (
                <dd className="tabular text-xs text-dim">
                  {card.price} cr × {itemCount} {itemCount === 1 ? "document" : "documents"}
                </dd>
              )}
            </div>
            <div className="bg-surface px-4 py-3">
              <dt className="text-xs text-dim">Agent must deliver within</dt>
              <dd className="display tabular mt-0.5 text-2xl">{card.maxExecutionSeconds}s</dd>
            </div>
          </dl>
          <p className="text-xs text-dim">
            The price is fixed by the agent and frozen in escrow, not charged, until you accept the result.
            {itemCount > 0 && " With documents, each one is an Item that is paid, or refunded, on its own."}
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
            {submitting ? "Booking…" : `Book and freeze ${total} cr`}
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
