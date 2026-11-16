"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { Skeleton } from "@/components/ui";
import { ContractValueView } from "@/components/AcceptanceTerms";
import { windowInWords, type DraftState, type RequirementFate } from "@/lib/extraction";
import type { ContractDTO, ReviewWindowsDTO, TemplateDTO } from "@/lib/types";

/**
 * The booking card's "What booking agrees to" (#101, spec D11/D28c). Booking is acceptance, so before
 * the Client confirms it says in plain words what is checked automatically, which requirements in the
 * description nobody checks automatically (the Client judges them), and the review and response
 * windows that follow delivery. The windows come from the server, never restated here.
 */
interface Props {
  /** The Contract the booking freezes; null = the agent's own output terms. */
  contract: ContractDTO | null;
  /** The terms the Client set in Output terms (null = none): a term there is theirs, whatever was extracted. */
  own?: ContractDTO | null;
  template?: TemplateDTO;
  draft: DraftState | null;
  fates: RequirementFate[];
}

type WindowsState = { status: "loading" } | { status: "ready"; windows: ReviewWindowsDTO } | { status: "error" };

function useReviewWindows(): WindowsState {
  const [state, setState] = useState<WindowsState>({ status: "loading" });
  useEffect(() => {
    let live = true;
    api<ReviewWindowsDTO>("/catalogue/review-windows")
      .then((windows) => live && setState({ status: "ready", windows }))
      .catch(() => live && setState({ status: "error" }));
    return () => {
      live = false;
    };
  }, []);
  return state;
}

function Heading({ id, children }: { id: string; children: React.ReactNode }) {
  return <h3 id={id} className="text-sm font-semibold text-fg">{children}</h3>;
}

export function BookingChecks({ contract, own = null, template, draft, fates }: Props) {
  const windows = useReviewWindows();
  const checked = fates.filter((f) => f.kind === "checked");
  const yours = fates.filter((f) => f.kind === "yours");
  const unavailable = draft !== null && (draft.kind === "unavailable" || draft.draft.extraction.status === "UNAVAILABLE");
  const params = contract ? Object.entries(contract.params) : [];

  return (
    <section aria-labelledby="booking-checks-h" className="mt-4 space-y-4 border-t border-line pt-4">
      <div>
        <h2 id="booking-checks-h" className="text-base font-semibold text-fg">What booking agrees to</h2>
        <p className="mt-0.5 text-sm text-muted">
          Booking is your acceptance of these terms. The result is held to them, and nothing more is asked of you.
        </p>
      </div>

      <div className="space-y-2">
        <Heading id="checks-auto-h">Checked automatically, before you see the result</Heading>
        <ul aria-labelledby="checks-auto-h" className="divide-y divide-line rounded-lg border border-line bg-surface">
          {!contract && (
            <li className="px-3 py-2.5 text-sm text-muted">
              The agent&apos;s own output terms: its result is validated against its output contract.
            </li>
          )}
          {contract && params.length === 0 && (
            <li className="px-3 py-2.5 text-sm text-muted">
              {template ? `The ${template.title} template's own checks.` : "The template's own checks."} No further terms
              are set.
            </li>
          )}
          {params.map(([name, value]) => {
            const label = template?.parameters.find((p) => p.name === name)?.label ?? name;
            const theirs = own !== null && own.template === contract?.template && name in own.params;
            const from = checked.find((f) => f.requirement.params && name in f.requirement.params);
            return (
              <li key={name} className="grid gap-1 px-3 py-2.5 sm:grid-cols-[11rem_1fr] sm:gap-4">
                <span className="text-sm text-dim">{label}</span>
                <span className="min-w-0 text-sm">
                  <ContractValueView name={name} value={value} />
                  <span className="mt-0.5 block text-xs text-dim">
                    {theirs ? (
                      "From your output terms"
                    ) : from ? (
                      <>From your description: &ldquo;{from.requirement.text}&rdquo;</>
                    ) : (
                      "From your description"
                    )}
                  </span>
                </span>
              </li>
            );
          })}
        </ul>
      </div>

      {unavailable ? (
        <p className="rounded-md border border-amber/30 bg-amber/10 px-3 py-2 text-sm text-fg">
          We couldn&apos;t read the requirements in your description just now, so none of them is checked
          automatically. The agent still gets your description as written, and you judge the result against it.
        </p>
      ) : yours.length > 0 ? (
        <div className="space-y-2">
          <Heading id="checks-yours-h">Not checked automatically: you judge these</Heading>
          <ul aria-labelledby="checks-yours-h" className="space-y-2">
            {yours.map((f, i) => (
              <li key={i} className="rounded-lg border border-l-4 border-line border-l-amber bg-surface px-3 py-2.5">
                <p className="text-sm text-fg">&ldquo;{f.requirement.text}&rdquo;</p>
                <p className="mt-0.5 text-xs text-dim">{f.why}</p>
              </li>
            ))}
          </ul>
          <p className="text-xs text-dim">
            They stay in the description the agent gets. You judge them when you review the result, and they count
            as evidence if it goes to arbitration.
          </p>
        </div>
      ) : draft !== null && fates.length === 0 ? (
        <p className="text-sm text-dim">
          We found no specific requirement in your description to check automatically. You still judge the result
          against it when you review.
        </p>
      ) : null}

      <div className="space-y-1.5">
        <Heading id="checks-after-h">After delivery</Heading>
        {windows.status === "loading" && (
          <div role="status" className="space-y-1.5">
            <span className="sr-only">Loading the review windows</span>
            <Skeleton className="h-4 w-4/5" />
            <Skeleton className="h-4 w-3/5" />
          </div>
        )}
        {windows.status === "error" && (
          <p className="text-sm text-muted">
            You have a limited time to accept or reject the result; the task page counts it down. If you don&apos;t
            decide, it is accepted and paid for you.
          </p>
        )}
        {windows.status === "ready" && (
          <>
            <p className="text-sm text-muted">
              You have {windowInWords(windows.windows.reviewSeconds)}{" "}
              to accept or reject the result. If you don&apos;t decide, it is accepted and paid for you.
            </p>
            <p className="text-sm text-muted">
              If you reject it, the agent has {windowInWords(windows.windows.agentResponseSeconds)} to respond: revise
              it, concede, or stand by it. If it stands by it, you have{" "}
              {windowInWords(windows.windows.clientDecisionSeconds)} to accept it or ask for arbitration.
            </p>
          </>
        )}
      </div>
    </section>
  );
}
