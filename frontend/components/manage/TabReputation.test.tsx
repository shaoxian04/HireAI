import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { TabReputation } from "@/components/manage/TabReputation";
import type { AgentReputationDTO } from "@/lib/types";

const apiMock = vi.fn();
vi.mock("@/lib/api", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api")>("@/lib/api");
  return { ...actual, api: (...args: unknown[]) => apiMock(...args) };
});

function breakdown(over: Partial<AgentReputationDTO> = {}): AgentReputationDTO {
  return {
    score: 78,
    reliability: { value: 90, sampleCount: 20 },
    satisfaction: { value: 50, sampleCount: 0 },
    unproven: false,
    recentEvents: [],
    ...over,
  };
}

describe("TabReputation", () => {
  beforeEach(() => apiMock.mockReset());

  it("shows both components separately with their sample counts", async () => {
    apiMock.mockResolvedValue(breakdown());
    render(<TabReputation agentId="a-1" />);

    expect(await screen.findByText(/Reliability · 20 tasks/i)).toBeInTheDocument();
    expect(screen.getByText(/Satisfaction · 0 ratings/i)).toBeInTheDocument();
  });

  it("reads a zero-event agent as unproven rather than excellent", async () => {
    apiMock.mockResolvedValue(
      breakdown({
        unproven: true,
        reliability: { value: 50, sampleCount: 0 },
        satisfaction: { value: 50, sampleCount: 0 },
      }),
    );
    render(<TabReputation agentId="a-1" />);

    expect(await screen.findByText(/Unproven/i)).toBeInTheDocument();
    expect(screen.getByText(/has not completed any tasks yet/i)).toBeInTheDocument();
  });

  /**
   * Regression: caught on a live run. Shrinkage holds a component near the prior until evidence
   * accumulates, so a flawless agent with ONE delivered task reads ~58. Calling that "failing to
   * deliver" mistakes absence of evidence for evidence of failure — the same error the
   * two-component split exists to prevent, inverted.
   */
  it("does not accuse a low-sample agent of failing to deliver", async () => {
    apiMock.mockResolvedValue(
      breakdown({
        score: 56.52,
        reliability: { value: 58.3, sampleCount: 1 },
        satisfaction: { value: 52.3, sampleCount: 1 },
      }),
    );
    render(<TabReputation agentId="a-1" />);

    await waitFor(() => expect(screen.getByText(/missing evidence/i)).toBeInTheDocument());
    expect(screen.queryByText(/failing to deliver/i)).not.toBeInTheDocument();
  });

  it("does call out a genuinely weak delivery record once there is evidence", async () => {
    apiMock.mockResolvedValue(
      breakdown({ reliability: { value: 32, sampleCount: 25 } }),
    );
    render(<TabReputation agentId="a-1" />);

    expect(await screen.findByText(/failing to deliver/i)).toBeInTheDocument();
  });

  /** The payoff of keeping the components apart: "delivers, but clients dislike it". */
  it("distinguishes unimpressed clients from a delivery failure", async () => {
    apiMock.mockResolvedValue(
      breakdown({
        reliability: { value: 95, sampleCount: 30 },
        satisfaction: { value: 28, sampleCount: 20 },
      }),
    );
    render(<TabReputation agentId="a-1" />);

    expect(await screen.findByText(/quality problem, not a reliability one/i)).toBeInTheDocument();
  });

  it("renders the event stream with its component tag", async () => {
    apiMock.mockResolvedValue(
      breakdown({
        recentEvents: [
          {
            id: "e-1",
            taskId: "251d4321-aaaa-bbbb-cccc-dddddddddddd",
            eventType: "RATING",
            quality: 0.75,
            weight: 1,
            occurredAt: "2026-08-13T02:45:00Z",
          },
          {
            id: "e-2",
            taskId: "251d4321-aaaa-bbbb-cccc-dddddddddddd",
            eventType: "SPEC_VIOLATION",
            quality: 0,
            weight: 1,
            occurredAt: "2026-08-13T02:44:00Z",
          },
        ],
      }),
    );
    render(<TabReputation agentId="a-1" />);

    // quality 0.75 must read back as the 4 stars the client actually left.
    expect(await screen.findByText("4★ rating")).toBeInTheDocument();
    expect(screen.getByText("Failed validation")).toBeInTheDocument();
    expect(screen.getByText("satisfaction")).toBeInTheDocument();
    expect(screen.getByText("reliability")).toBeInTheDocument();
  });
});
