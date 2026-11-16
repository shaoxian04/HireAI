import { describe, expect, it } from "vitest";
import { invoiceContract, invoiceTerm, offersBusinessValid } from "@/lib/invoice";
import type { OfferDTO } from "@/lib/types";

const textOnly: OfferDTO = { price: 1, terms: [{ template: "text_basic", version: 1, params: {} }] };
const faithfulOnly: OfferDTO = { price: 1, terms: [{ template: "ap_invoice_extraction", version: 1, params: {} }] };
const both: OfferDTO = {
  price: 1,
  terms: [{ template: "ap_invoice_extraction", version: 1, params: { expectation: { oneOf: ["business_valid", "faithful_to_source"] } } }],
};

describe("the invoice Contract a booking freezes", () => {
  it("exists only when the agent offers invoice extraction", () => {
    expect(invoiceTerm(textOnly)).toBeNull();
    expect(invoiceContract(textOnly, "faithful_to_source")).toBeNull();
  });

  it("sends faithful to source as the unset default, which every invoice offer covers", () => {
    expect(invoiceContract(faithfulOnly, "faithful_to_source")).toEqual({ template: "ap_invoice_extraction", version: 1, params: {} });
  });

  it("asks for a business-valid result only when the offer accepts one", () => {
    expect(offersBusinessValid(faithfulOnly)).toBe(false);
    expect(invoiceContract(faithfulOnly, "business_valid")?.params).toEqual({});
    expect(offersBusinessValid(both)).toBe(true);
    expect(invoiceContract(both, "business_valid")?.params).toEqual({ expectation: "business_valid" });
    expect(offersBusinessValid({ price: 1, terms: [{ template: "ap_invoice_extraction", version: 1, params: { expectation: { any: true } } }] })).toBe(true);
  });
});
