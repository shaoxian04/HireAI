import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { TaskFailurePanel } from "@/components/TaskFailurePanel";
import type { ValidationReportDTO } from "@/lib/types";

describe("TaskFailurePanel", () => {
  it("renders nothing for a non-failure status", () => {
    const { container } = render(<TaskFailurePanel taskId="t-1" status="EXECUTING" budget={10} />);
    expect(container.firstChild).toBeNull();
  });

  it.each([
    ["SPEC_VIOLATION", /didn't meet the spec/i],
    ["TIMED_OUT", /ran out of time/i],
    ["FAILED", /couldn't reach the agent/i],
    ["CANCELLED", /no agent was available/i],
  ] as const)("renders %s headline and the refund line", (status, headline) => {
    render(<TaskFailurePanel taskId="t-1" status={status} budget={17} />);
    expect(screen.getByText(headline)).toBeTruthy();
    expect(screen.getByText(/17 cr refunded/i)).toBeTruthy();
  });

  it.each([
    ["FAILED", "AGENT_UNREACHABLE", /couldn't reach the agent/i],
    ["FAILED", "AGENT_BUSY", /agent was busy/i],
    ["CANCELLED", "AGENT_UNAVAILABLE", /agent you chose isn't available/i],
    ["FAILED", "NO_ALTERNATIVE_AGENT", /no other agent could take it/i],
    ["CANCELLED", "NO_ELIGIBLE_AGENT", /no agent was available/i],
    ["CANCELLED", "DISPATCH_ABANDONED", /couldn't hand your task to an agent/i],
    ["TIMED_OUT", "DISPATCH_ABANDONED", /couldn't hand your task to an agent/i],
    ["TIMED_OUT", "EXECUTION_TIMEOUT", /ran out of time/i],
    ["FAILED", "AGENT_REPORTED_FAILURE", /agent couldn't complete/i],
    ["SPEC_VIOLATION", "SPEC_VIOLATION", /didn't meet the spec/i],
  ] as const)("explains %s with reason %s in plain words", (status, failureReason, headline) => {
    render(<TaskFailurePanel taskId="t-1" status={status} failureReason={failureReason} budget={8} />);
    expect(screen.getByRole("heading", { name: headline })).toBeTruthy();
    expect(screen.getByText(/8 cr refunded/i)).toBeTruthy();
  });

  it("explains the reason rather than the status when they differ", () => {
    // A timeout while still queued is not the agent running out of time.
    render(<TaskFailurePanel taskId="t-1" status="TIMED_OUT" failureReason="DISPATCH_ABANDONED" budget={5} />);
    expect(screen.queryByText(/ran out of time/i)).toBeNull();
  });

  it("shows the failed-check reason for spec-violation when detail is provided", () => {
    const detail: ValidationReportDTO = {
      verdict: "FAIL",
      checks: [
        { rule: "schema", passed: true, detail: null },
        { rule: "format", passed: false, detail: "expected FILE, got none" },
      ],
    };
    render(<TaskFailurePanel taskId="t-1" status="SPEC_VIOLATION" budget={10} detail={detail} />);
    expect(screen.getByText(/format/)).toBeTruthy();
    expect(screen.getByText(/expected FILE, got none/)).toBeTruthy();
    // Passed checks are not listed.
    expect(screen.queryByText(/^schema$/)).toBeNull();
  });

  it.each(["SPEC_VIOLATION", "TIMED_OUT", "FAILED", "CANCELLED"] as const)(
    "offers to choose another agent for this task when it ended %s",
    (status) => {
      render(<TaskFailurePanel taskId="t-42" status={status} failureReason="AGENT_BUSY" budget={10} />);
      const link = screen.getByRole("link", { name: "Choose another agent" });
      expect(link.getAttribute("href")).toBe("/client/tasks/new?rebook=t-42");
    },
  );

  it("omits the drawer when there is no detail", () => {
    render(<TaskFailurePanel taskId="t-1" status="SPEC_VIOLATION" budget={10} detail={null} />);
    expect(screen.queryByText(/show what failed/i)).toBeNull();
  });
});
