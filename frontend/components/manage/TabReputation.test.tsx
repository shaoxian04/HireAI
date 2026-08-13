import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { TabReputation } from "@/components/manage/TabReputation";
import type { AgentReputationDTO, ReputationComponentDTO } from "@/lib/types";

const apiMock = vi.fn();
vi.mock("@/lib/api", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api")>("@/lib/api");
  return { ...actual, api: (...args: unknown[]) => apiMock(...args) };
});

/**
 * Builds a component the way the server does, so the fixtures cannot drift into states the backend
 * would never produce. `priorWeight` is k/(k+n) with the real prior strengths — kR = 5 outcomes,
 * kS = 10 ratings, Satisfaction being held to the higher evidential bar.
 */
function component(value: number, sampleCount: number, k: number, weight: number): ReputationComponentDTO {
  return { value, sampleCount, priorWeight: k / (k + sampleCount), weight };
}

const reliability = (value: number, n: number) => component(value, n, 5, 0.7);
const satisfaction = (value: number, n: number) => component(value, n, 10, 0.3);

function breakdown(over: Partial<AgentReputationDTO> = {}): AgentReputationDTO {
  return {
    score: 78,
    reliability: reliability(90, 20),
    satisfaction: satisfaction(50, 0),
    unproven: false,
    recentEvents: [],
    ...over,
  };
}

describe("TabReputation", () => {
  beforeEach(() => apiMock.mockReset());

  it("shows both components separately with the evidence behind each", async () => {
    apiMock.mockResolvedValue(breakdown());
    render(<TabReputation agentId="a-1" />);

    expect(await screen.findByText("Reliability")).toBeInTheDocument();
    expect(screen.getByText("Satisfaction")).toBeInTheDocument();
    expect(screen.getByText("20 outcomes")).toBeInTheDocument();
    expect(screen.getByText(/No ratings yet/i)).toBeInTheDocument();
  });

  /**
   * The blend is policy (α), not a UI constant. Rendering it from the server's `weight` is what
   * stops the labels quietly lying the day someone tunes α in config.
   */
  it("labels the blend from the server rather than a hardcoded 70/30", async () => {
    apiMock.mockResolvedValue(
      breakdown({
        reliability: component(90, 20, 5, 0.55),
        satisfaction: component(70, 20, 10, 0.45),
      }),
    );
    render(<TabReputation agentId="a-1" />);

    expect(await screen.findByText("55% of score")).toBeInTheDocument();
    expect(screen.getByText("45% of score")).toBeInTheDocument();
  });

  it("reads a zero-event agent as unproven rather than excellent", async () => {
    apiMock.mockResolvedValue(
      breakdown({
        unproven: true,
        reliability: reliability(50, 0),
        satisfaction: satisfaction(50, 0),
      }),
    );
    render(<TabReputation agentId="a-1" />);

    expect(await screen.findByText("Unproven")).toBeInTheDocument();
    expect(screen.getByText(/Nothing has run through this agent yet/i)).toBeInTheDocument();
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
        reliability: reliability(58.3, 1),
        satisfaction: satisfaction(52.3, 1),
      }),
    );
    render(<TabReputation agentId="a-1" />);

    await waitFor(() => expect(screen.getByText("Too early to tell")).toBeInTheDocument());
    expect(screen.getByText(/missing evidence, not a bad result/i)).toBeInTheDocument();
    expect(screen.queryByText(/failing to/i)).not.toBeInTheDocument();
  });

  /** The same guard expressed as the number: one sample is mostly prior, and the panel says so. */
  it("shows how much of a component is actually earned", async () => {
    apiMock.mockResolvedValue(
      breakdown({ reliability: reliability(58.3, 1), satisfaction: satisfaction(52.3, 1) }),
    );
    render(<TabReputation agentId="a-1" />);

    // 1 - 5/(5+1) = 16.7% earned; 1 - 10/(10+1) = 9.1%.
    expect(await screen.findByText("17% earned")).toBeInTheDocument();
    expect(screen.getByText("9% earned")).toBeInTheDocument();
  });

  it("does call out a genuinely weak delivery record once there is evidence", async () => {
    apiMock.mockResolvedValue(breakdown({ reliability: reliability(32, 25) }));
    render(<TabReputation agentId="a-1" />);

    expect(await screen.findByText("Delivery is the problem")).toBeInTheDocument();
  });

  /** The payoff of keeping the components apart: "delivers, but clients dislike it". */
  it("distinguishes unimpressed clients from a delivery failure", async () => {
    apiMock.mockResolvedValue(
      breakdown({ reliability: reliability(95, 30), satisfaction: satisfaction(28, 20) }),
    );
    render(<TabReputation agentId="a-1" />);

    expect(await screen.findByText(/quality problem, not a reliability one/i)).toBeInTheDocument();
  });

  /**
   * "Silence ≠ perfection" is the load-bearing insight of the whole model, so the panel has to say
   * out loud that an unrated agent is being held down, not rewarded.
   */
  it("tells a well-delivering but unrated agent that silence is not a compliment", async () => {
    apiMock.mockResolvedValue(
      breakdown({ reliability: reliability(95, 30), satisfaction: satisfaction(50, 0) }),
    );
    render(<TabReputation agentId="a-1" />);

    expect(await screen.findByText("Delivering, unrated")).toBeInTheDocument();
    expect(screen.getByText(/Silence is not a compliment/i)).toBeInTheDocument();
  });

  it("renders the event stream with its component and what it counted for", async () => {
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

    // quality 0.75 must read back as the 4 stars the client actually left...
    expect(await screen.findByText("4★ rating")).toBeInTheDocument();
    // ...and as the 75 it contributed, which is why 4 stars does not read as full marks.
    expect(screen.getByLabelText("counted 75 out of 100")).toBeInTheDocument();
    expect(screen.getByText("Failed validation")).toBeInTheDocument();
    expect(screen.getByLabelText("counted 0 out of 100")).toBeInTheDocument();
    expect(screen.getByText(/Satisfaction · task 251d4321/)).toBeInTheDocument();
  });
});
