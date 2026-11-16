import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { ContractTermsField } from "@/components/ContractTermsField";
import { OfferEditor } from "@/components/OfferEditor";
import { refOf } from "@/components/AcceptanceTerms";
import type { TemplateDTO } from "@/lib/types";

/** A Template line in transition: report@2 is current, report@1 still takes Contracts, memo@1 is closed. */
function template(key: string, version: number, title: string, lifecycle: Partial<TemplateDTO>): TemplateDTO {
  return {
    key, version, title, description: null, outputShape: "TEXT", checks: [], parameters: [],
    expectationMode: "NONE", rulingSettlement: { FULFILLED: 1, PARTIALLY_FULFILLED: 0.5, NOT_FULFILLED: 0 },
    status: "ACTIVE", publishedAt: "2026-11-01T00:00:00Z", ...lifecycle,
  };
}

const LINE = [
  template("text_basic", 1, "Plain text", { current: true, takesNewContracts: true }),
  template("report", 1, "Report (previous)", {
    current: false, takesNewContracts: true, takesNewContractsUntil: "2026-12-01T00:00:00Z",
  }),
  template("report", 2, "Report", { current: true, takesNewContracts: true }),
  template("memo", 1, "Memo (closed)", { current: false, takesNewContracts: false }),
];

describe("Template version pickers (spec D36)", () => {
  it("offers Clients only the current version of each Template", () => {
    const byRef = new Map(LINE.map((t) => [refOf(t.key, t.version), t]));
    render(<ContractTermsField templates={{ status: "ready", byRef }} value={null} onChange={() => {}} />);

    const options = screen.getAllByRole("option").map((o) => o.textContent);
    expect(options).toContain("Report");
    expect(options).not.toContain("Report (previous)");
    expect(options).not.toContain("Memo (closed)");
  });

  it("lets a Builder name a version in transition but not a closed one", () => {
    render(
      <OfferEditor
        templates={LINE}
        value={{}}
        own={{ ref: "text_basic@1", params: {} }}
        problems={{}}
        onChange={() => {}}
      />,
    );

    expect(screen.getAllByText("Report (previous)").length).toBeGreaterThan(0);
    expect(screen.queryByText("Memo (closed)")).toBeNull();
  });
});
