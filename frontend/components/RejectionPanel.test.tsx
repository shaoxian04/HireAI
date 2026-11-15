import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw/handlers";
import { AgentResponsePending, ClientDecisionPanel, RevisedResultNotice } from "@/components/RejectionPanel";
import type { RejectionDTO, TaskDTO } from "@/lib/types";

beforeAll(() => server.listen({ onUnhandledRequest: "warn" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const NOW = new Date("2026-11-15T10:00:00Z");

const base: RejectionDTO = {
  round: 1,
  reasonCategory: "B_FACTUAL",
  reason: "the dates are wrong",
  rejectedAt: "2026-11-15T09:00:00Z",
  responseDeadline: "2026-11-15T11:00:00Z",
  revisionAllowed: true,
  responseSilent: false,
  decisionSilent: false,
};

const stoodBy: RejectionDTO = {
  ...base,
  response: "STAND_BY",
  responseReason: "the dates come from the source document",
  respondedAt: "2026-11-15T09:30:00Z",
  decisionDeadline: "2026-11-16T09:30:00Z",
};

describe("AgentResponsePending", () => {
  it("quotes the rejection and shows how long the agent has to respond", () => {
    render(<AgentResponsePending rejection={base} now={NOW} />);
    expect(screen.getByText(/contains factual errors/i)).toBeTruthy();
    expect(screen.getByText("“the dates are wrong”")).toBeTruthy();
    expect(screen.getByText("1h left")).toBeTruthy();
    expect(screen.getByText(/send a revised result/i)).toBeTruthy();
  });

  it("says the agent can no longer revise after one revision", () => {
    render(<AgentResponsePending rejection={{ ...base, round: 2, revisionAllowed: false }} now={NOW} />);
    expect(screen.queryByText(/send a revised result/i)).toBeNull();
    expect(screen.getByText(/can't revise again/i)).toBeTruthy();
  });

  it("explains that silence counts as standing by once the window closed", () => {
    render(<AgentResponsePending rejection={base} now={new Date("2026-11-15T11:00:00Z")} />);
    expect(screen.getByText(/didn't respond in time/i)).toBeTruthy();
  });
});

describe("ClientDecisionPanel", () => {
  it("shows the agent's reason, the time left and both choices", () => {
    render(<ClientDecisionPanel taskId="t-1" budget={10} rejection={stoodBy} now={NOW} onDecided={vi.fn()} />);
    expect(screen.getByText("“the dates come from the source document”")).toBeTruthy();
    expect(screen.getByText("23h 30m left")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Accept result" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Request AI arbitration" })).toBeTruthy();
  });

  it("says when the agent stood by silently", () => {
    render(
      <ClientDecisionPanel
        taskId="t-1"
        rejection={{ ...stoodBy, responseReason: null, responseSilent: true }}
        now={NOW}
        onDecided={vi.fn()}
      />,
    );
    expect(screen.getByText(/didn't respond within its window/i)).toBeTruthy();
  });

  it("requests arbitration after confirming", async () => {
    const user = userEvent.setup();
    let called = false;
    server.use(
      http.post("*/api/tasks/:id/arbitration", () => {
        called = true;
        return HttpResponse.json({ success: true, code: "OK", message: null, data: { id: "t-1", status: "DISPUTED" } });
      }),
    );
    let decided: TaskDTO | null = null;
    render(<ClientDecisionPanel taskId="t-1" rejection={stoodBy} now={NOW} onDecided={(t) => (decided = t)} />);

    await user.click(screen.getByRole("button", { name: "Request AI arbitration" }));
    expect(screen.getByText(/no deposit is taken/i)).toBeTruthy();
    await user.click(screen.getByRole("button", { name: "Request arbitration" }));

    await waitFor(() => expect(decided).not.toBeNull());
    expect(called).toBe(true);
    expect((decided as TaskDTO | null)!.status).toBe("DISPUTED");
  });

  it("shows the error when the decision fails", async () => {
    const user = userEvent.setup();
    server.use(
      http.post("*/api/tasks/:id/arbitration", () =>
        HttpResponse.json({ success: false, code: "DOMAIN_RULE_VIOLATION", message: "Decision window closed", data: null }, { status: 409 }),
      ),
    );
    render(<ClientDecisionPanel taskId="t-1" rejection={stoodBy} now={NOW} onDecided={vi.fn()} />);
    await user.click(screen.getByRole("button", { name: "Request AI arbitration" }));
    await user.click(screen.getByRole("button", { name: "Request arbitration" }));
    expect(await screen.findByRole("alert")).toBeTruthy();
  });

  it("takes the choices away once the decision window closed", () => {
    render(
      <ClientDecisionPanel taskId="t-1" rejection={stoodBy} now={new Date("2026-11-16T09:30:00Z")} onDecided={vi.fn()} />,
    );
    expect(screen.getByText(/decision window has closed/i)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Request AI arbitration" })).toBeNull();
  });
});

describe("RevisedResultNotice", () => {
  it("says this is the agent's revision and that it can't revise again", () => {
    render(<RevisedResultNotice rejection={{ ...base, response: "REVISE" }} />);
    expect(screen.getByText(/revised result/i)).toBeTruthy();
    expect(screen.getByText(/not revise a second time/i)).toBeTruthy();
  });
});
