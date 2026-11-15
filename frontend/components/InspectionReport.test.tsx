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

  it("does not claim the client judged a result that was accepted automatically", () => {
    render(
      <InspectionReport
        report={{ verdict: "PASS", checks: [] }}
        spec={spec as never}
        awaitingYou={false}
        autoAccepted
      />,
    );
    expect(screen.queryByText(/judged by you/i)).toBeNull();
    expect(screen.getByText(/not judged: the review window closed/i)).toBeInTheDocument();
  });

  it("marks a check no machine could decide as yours, and shows what the agent flagged", () => {
    render(
      <InspectionReport
        report={{
          verdict: "REVIEW",
          checks: [
            { rule: "IMAGE_FORMAT", passed: true, detail: "the file is image/png", outcome: "PASS" },
            {
              rule: "IMAGE_BACKGROUND_COLOUR",
              passed: false,
              detail: "the background is #ff0000; #ffffff asked. The Agent reported: white on white is invisible",
              outcome: "REVIEW",
            },
          ],
          anomalies: [{ check: "IMAGE_BACKGROUND_COLOUR", description: "white on white is invisible" }],
        }}
        spec={spec}
        awaitingYou
      />,
    );
    expect(screen.getByText(/verdict: sent to your review/i)).toBeInTheDocument();
    expect(screen.getByText(/for you to judge:/i)).toBeInTheDocument();
    expect(screen.getByText("The agent flagged")).toBeInTheDocument();
    expect(screen.getByText(/the agent is paid only then/i)).toBeInTheDocument();
  });

  it("lists Findings apart from the checks, as never changing the agent's pay", () => {
    render(
      <InspectionReport
        report={{
          verdict: "PASS",
          checks: [{ rule: "IMAGE_FORMAT", passed: true, detail: null, outcome: "PASS" }],
          findings: [{ code: "DUPLICATE_BRIEF", detail: "on task t-1" }],
        }}
        spec={spec}
        awaitingYou
      />,
    );
    expect(screen.getByText("You have sent this exact brief before")).toBeInTheDocument();
    expect(screen.getByText(/never changes what the agent is paid/i)).toBeInTheDocument();
    expect(screen.getByText(/verdict: passed/i)).toBeInTheDocument();
  });

  it("says when the result was reworked before the client saw it", () => {
    render(
      <InspectionReport
        report={{
          verdict: "PASS",
          checks: [{ rule: "IMAGE_BACKGROUND_COLOUR", passed: true, detail: null, outcome: "PASS" }],
          previousAttempts: [
            { attemptNo: 1, verdict: "FAIL", failedChecks: [{ rule: "IMAGE_BACKGROUND_COLOUR", passed: false, detail: "red", outcome: "REJECT" }] },
          ],
        }}
        spec={spec}
        awaitingYou
      />,
    );
    expect(screen.getByText(/reworked once/i)).toBeInTheDocument();
    expect(screen.getByText(/attempt 1 failed the background is the colour the contract asks/i)).toBeInTheDocument();
  });
});
