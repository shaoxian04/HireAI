import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { InspectionReport } from "./InspectionReport";

const spec = { format: "JSON", schema: null, acceptanceCriteria: "A non-empty summary field" } as never;

describe("InspectionReport", () => {
  it("lists each automated check in plain words and hands acceptance to the client", () => {
    render(
      <InspectionReport
        report={{
          verdict: "PASS",
          checks: [
            { rule: "FORMAT_JSON_PARSEABLE", passed: true, detail: null },
            { rule: "SCHEMA_SKIPPED", passed: true, detail: "no schema" },
          ],
        }}
        spec={spec}
        awaitingYou
      />,
    );
    expect(screen.getByText("The result is valid JSON")).toBeInTheDocument();
    expect(screen.getByText(/no schema in the contract/i)).toBeInTheDocument();
    expect(screen.getByText(/yours to judge/i)).toBeInTheDocument();
    expect(screen.getByText("A non-empty summary field")).toBeInTheDocument();
    expect(screen.getByText(/verdict: passed/i)).toBeInTheDocument();
  });

  it("shows a failed check with its reason, and says acceptance was already judged once decided", () => {
    render(
      <InspectionReport
        report={{ verdict: "FAIL", checks: [{ rule: "SCHEMA_MATCH", passed: false, detail: "missing summary" }] }}
        spec={{ ...(spec as object), acceptanceCriteria: null } as never}
        awaitingYou={false}
      />,
    );
    expect(screen.getByText(/failed:/i)).toBeInTheDocument();
    expect(screen.getByText("missing summary")).toBeInTheDocument();
    expect(screen.getByText(/judged by you when you reviewed it/i)).toBeInTheDocument();
    expect(screen.getByText(/sets no acceptance criteria/i)).toBeInTheDocument();
  });
});
