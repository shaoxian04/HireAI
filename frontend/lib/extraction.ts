import type { ContractDTO, ContractDraftDTO, ContractValue, ExtractionDTO, RequirementDTO } from "@/lib/types";

/**
 * Requirement extraction on the web (#101, spec S6/D28c). Before the shortlist search the description
 * goes to `POST /contracts/draft`; the terms it maps become part of the Contract the shortlist must
 * cover and the booking freezes. Booking is acceptance, so the booking card says what each requirement
 * became. These helpers are the pure part of that.
 */

/** What extraction gave: a draft, or nothing usable (the extractor or the call failed). */
export type DraftState = { kind: "ready"; draft: ContractDraftDTO } | { kind: "unavailable" };

/** The draft request: the Client's own Template when they chose one, else the platform's text default. */
export function draftRequest(title: string, description: string, own: ContractDTO | null) {
  return { title, description, ...(own ? { template: own.template } : {}) };
}

/** Same request again = the same extraction; a re-search after a relax must not bring a dropped term back. */
export function draftKey(title: string, description: string, own: ContractDTO | null) {
  return JSON.stringify(draftRequest(title, description, own));
}

function usable(state: DraftState | null): ContractDraftDTO | null {
  return state?.kind === "ready" && state.draft.extraction.status === "EXTRACTED" ? state.draft : null;
}

/**
 * The Contract to search and book with: the extracted terms, with the Client's own terms on top (a term
 * they set themselves wins), minus the terms they dropped to find agents. Null keeps the agent's own
 * terms: nothing was chosen and nothing was extracted.
 */
export function effectiveContract(state: DraftState | null, own: ContractDTO | null, dropped: string[]): ContractDTO | null {
  const draft = usable(state);
  if (!draft) return own;
  const base = draft.contract;
  if (own && (own.template !== base.template || own.version !== base.version)) return own;
  // A dropped term is dropped from the extracted ones only: one the Client sets again is theirs.
  const extracted: Record<string, ContractValue> = { ...base.params };
  for (const name of dropped) delete extracted[name];
  const params: Record<string, ContractValue> = { ...extracted, ...(own?.params ?? {}) };
  if (!own && Object.keys(params).length === 0) return null;
  return { template: base.template, version: base.version, params };
}

export type RequirementFate =
  | { kind: "checked"; requirement: RequirementDTO }
  | { kind: "yours"; requirement: RequirementDTO; why: string };

function sameValue(a: ContractValue | undefined, b: ContractValue) {
  return JSON.stringify(a) === JSON.stringify(b);
}

/**
 * What became of each requirement found in the description: checked automatically when every term it
 * set is in the booked Contract as it set it, else left to the Client, saying why.
 */
export function requirementFates(state: DraftState | null, booked: ContractDTO | null, dropped: string[]): RequirementFate[] {
  const draft = usable(state);
  if (!draft) return [];
  return draft.extraction.requirements.map((requirement) => {
    if (!requirement.mapped) {
      return { kind: "yours", requirement, why: requirement.unmappedReason ?? "No automatic check fits it." };
    }
    const set = Object.entries(requirement.params ?? {});
    const kept =
      booked &&
      booked.template === draft.contract.template &&
      set.every(([name, value]) => sameValue(booked.params[name], value));
    if (kept) return { kind: "checked", requirement };
    const why = set.some(([name]) => dropped.includes(name))
      ? "You dropped this term to find agents, so it is not checked automatically."
      : "Your output terms set this differently, and yours are the ones checked.";
    return { kind: "yours", requirement, why };
  });
}

/**
 * The draft's extraction to keep on the booked task (D7), so its page can say which requirements the
 * Client judges. Only when the booking is held to the Template version the draft was read against.
 */
export function bookedExtraction(state: DraftState | null, booked: ContractDTO | null): ExtractionDTO | null {
  if (state?.kind !== "ready" || !booked) return null;
  const extraction = state.draft.extraction;
  return extraction.template === booked.template && extraction.version === booked.version ? extraction : null;
}

/**
 * On the task page: the requirements found in the description that the frozen Contract does not check
 * as written (not mapped, or dropped or set differently before booking). The Client judges these.
 */
export function requirementsYouJudge(
  extraction: ExtractionDTO | null | undefined,
  contract: ContractDTO | null | undefined,
): { text: string; why: string }[] {
  if (!extraction) return [];
  return extraction.requirements.flatMap((requirement) => {
    if (!requirement.mapped) {
      return [{ text: requirement.text, why: requirement.unmappedReason ?? "No automatic check fits it." }];
    }
    const kept =
      !!contract &&
      contract.template === extraction.template &&
      Object.entries(requirement.params ?? {}).every(([name, value]) => sameValue(contract.params[name], value));
    return kept
      ? []
      : [{ text: requirement.text, why: "The terms you booked don't check it as written, so it isn't checked automatically." }];
  });
}

/** A window in words: "24 hours", "1 hour", "30 minutes". */
export function windowInWords(seconds: number) {
  const plural = (n: number, unit: string) => `${n} ${unit}${n === 1 ? "" : "s"}`;
  if (seconds >= 3600 && seconds % 3600 === 0) return plural(seconds / 3600, "hour");
  if (seconds >= 60 && seconds % 60 === 0) return plural(seconds / 60, "minute");
  return plural(seconds, "second");
}
