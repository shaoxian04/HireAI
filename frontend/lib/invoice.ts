import type { ContractDTO, OfferDTO, OfferRange } from "@/lib/types";

/** The first vertical Template (#106): one supplier invoice's fields, checked for shape and arithmetic. */
export const INVOICE_TEMPLATE = "ap_invoice_extraction";

export type InvoiceExpectation = "faithful_to_source" | "business_valid";

/** The agent's Offer term for invoice extraction, if it offers one. */
export function invoiceTerm(offer: OfferDTO | null | undefined) {
  return offer?.terms.find((t) => t.template === INVOICE_TEMPLATE) ?? null;
}

function accepts(range: OfferRange | undefined, value: string): boolean {
  if (!range) return false;
  if ("any" in range) return true;
  return "oneOf" in range && range.oneOf.includes(value);
}

/**
 * Whether the agent can be held to a business-valid result. Faithful to the source is the Template's default
 * and needs no term (left unset, every invoice Offer covers it); business-valid must be in the Offer.
 */
export function offersBusinessValid(offer: OfferDTO | null | undefined): boolean {
  return accepts(invoiceTerm(offer)?.params.expectation, "business_valid");
}

/** The invoice Contract a booking freezes; faithful to source is sent as the unset default. */
export function invoiceContract(offer: OfferDTO, expectation: InvoiceExpectation): ContractDTO | null {
  const term = invoiceTerm(offer);
  if (!term) return null;
  return {
    template: INVOICE_TEMPLATE,
    version: term.version,
    params: expectation === "business_valid" && offersBusinessValid(offer) ? { expectation: "business_valid" } : {},
  };
}
