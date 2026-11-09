import type { RulingCategory, RulingDecidedBy } from "./types";

const ENUMS = new Set<string>(["FULFILLED", "PARTIALLY_FULFILLED", "NOT_FULFILLED"]);

/** The ruling's written rationale, or null when there is none worth showing (blank, or just the enum). */
export function rationaleText(r: string | null | undefined): string | null {
  const t = r?.trim();
  return t && !ENUMS.has(t) ? t : null;
}

/** Who decided, in words. */
export const DECIDED_BY: Record<RulingDecidedBy, string> = {
  ARBITRATOR: "Arbitrator",
  ADMINISTRATOR: "Administrator",
  FALLBACK: "Platform fallback",
};

/** The ruling as a stamp: short verdict + what it does to the money + colour. */
export const RULING_STAMP: Record<RulingCategory, { stamp: string; money: string; tone: string }> = {
  FULFILLED: { stamp: "Fulfilled", money: "Builder paid", tone: "text-green" },
  PARTIALLY_FULFILLED: { stamp: "Split", money: "Credits split", tone: "text-amber" },
  NOT_FULFILLED: { stamp: "Refunded", money: "Client refunded", tone: "text-red" },
};
