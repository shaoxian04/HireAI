import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { TaskFailurePanel } from "@/components/TaskFailurePanel";
import type { ValidationReportDTO } from "@/lib/types";

describe("TaskFailurePanel", () => {
  it("renders nothing for a non-failure status", () => {
    const { container } = render(<TaskFailurePanel status="EXECUTING" budget={10} />);
    expect(container.firstChild).toBeNull();
  });

  it.each([
    ["SPEC_VIOLATION", /didn't meet the spec/i],
    ["TIMED_OUT", /ran out of time/i],
    ["FAILED", /couldn't reach the agent/i],
    ["CANCELLED", /no agent was available/i],
  ] as const)("renders %s headline and the refund line", (status, headline) => {
    render(<TaskFailurePanel status={status} budget={17} />);
    expect(screen.getByText(headline)).toBeTruthy();
    expect(screen.getByText(/17 cr refunded/i)).toBeTruthy();
  });

  it("shows the failed-check reason for spec-violation when detail is provided", () => {
    const detail: ValidationReportDTO = {
      verdict: "FAIL",
      checks: [
        { rule: "schema", passed: true, detail: null },
        { rule: "format", passed: false, detail: "expected FILE, got none" },
      ],
    };
    render(<TaskFailurePanel status="SPEC_VIOLATION" budget={10} detail={detail} />);
    expect(screen.getByText(/format/)).toBeTruthy();
    expect(screen.getByText(/expected FILE, got none/)).toBeTruthy();
    // Passed checks are not listed.
    expect(screen.queryByText(/^schema$/)).toBeNull();
  });

  it("omits the drawer when there is no detail", () => {
    render(<TaskFailurePanel status="SPEC_VIOLATION" budget={10} detail={null} />);
    expect(screen.queryByText(/show what failed/i)).toBeNull();
  });
});
