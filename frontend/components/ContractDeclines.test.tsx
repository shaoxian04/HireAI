import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { ContractDeclines, DispatchHistory } from "@/components/ContractDeclines";
import type { DispatchAttemptDTO } from "@/lib/types";

describe("ContractDeclines", () => {
  it("states the decline rate as a count and a percentage", () => {
    const { container } = render(<ContractDeclines rate={{ declined: 1, answered: 4, rate: 0.25 }} />);
    expect(container.textContent).toBe("Declined terms on 1 of 4 tasks (25%)");
  });

  it("says plainly when an agent has declined nothing", () => {
    const { container } = render(<ContractDeclines rate={{ declined: 0, answered: 1, rate: 0 }} />);
    expect(container.textContent).toBe("Declined terms on 0 of 1 task");
  });

  it("does not read an agent with no answers as never declining", () => {
    const { container } = render(<ContractDeclines rate={{ declined: 0, answered: 0, rate: null }} />);
    expect(container.textContent).toBe("No terms answered yet");
  });

  it("renders nothing when the rate is missing", () => {
    const { container } = render(<ContractDeclines rate={undefined} />);
    expect(container.textContent).toBe("");
  });

  it("flags a high decline rate", () => {
    render(<ContractDeclines rate={{ declined: 3, answered: 6, rate: 0.5 }} />);
    expect(screen.getByText(/declined terms on/i)).toHaveClass("text-amber");
  });
});

const attempt = (over: Partial<DispatchAttemptDTO>): DispatchAttemptDTO => ({
  attemptNumber: 1, agentId: "a1", agentName: "Ledger Bot", agentVersionId: "v1",
  outcome: "ACCEPTED", occurredAt: "2026-11-16T00:00:00Z", declineReason: null, ...over,
});

describe("DispatchHistory", () => {
  it("shows nothing when the first agent simply took the task", () => {
    const { container } = render(<DispatchHistory attempts={[attempt({})]} />);
    expect(container.textContent).toBe("");
  });

  it("lists each hand-off with the agent's decline reason", () => {
    render(<DispatchHistory attempts={[
      attempt({ outcome: "CONTRACT_DECLINED", declineReason: "I only summarise English text" }),
      attempt({ attemptNumber: 2, agentId: "a2", agentName: "Brief Co", outcome: "ACCEPTED" }),
    ]} />);
    const rows = screen.getAllByRole("listitem");
    expect(rows[0]).toHaveTextContent("Ledger Bot");
    expect(rows[0]).toHaveTextContent(/declined your terms/i);
    expect(rows[0]).toHaveTextContent("“I only summarise English text”");
    expect(rows[1]).toHaveTextContent("Brief Co");
    expect(rows[1]).toHaveTextContent(/took the task/i);
  });

  it("names a removed agent generically and labels every other outcome", () => {
    render(<DispatchHistory attempts={[
      attempt({ outcome: "BUSY", agentName: null }),
      attempt({ attemptNumber: 2, outcome: "UNREACHABLE" }),
      attempt({ attemptNumber: 3, outcome: "UNAVAILABLE" }),
    ]} />);
    const rows = screen.getAllByRole("listitem");
    expect(rows[0]).toHaveTextContent(/an agent.*was busy/i);
    expect(rows[1]).toHaveTextContent(/couldn.t be reached/i);
    expect(rows[2]).toHaveTextContent(/no longer available/i);
  });
});
