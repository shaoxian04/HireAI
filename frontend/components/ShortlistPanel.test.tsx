import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { ShortlistPanel } from "@/components/ShortlistPanel";
import type { AgentOptionDTO } from "@/lib/types";

const opt = (over: Partial<AgentOptionDTO>): AgentOptionDTO => ({
  agentId: "a", agentVersionId: "v", agentName: "Agent", tagline: null, logoUrl: null,
  price: 10, reputationScore: 80, availability: "AVAILABLE", outputFormat: "JSON",
  capabilityCategories: ["summarisation"], reliabilitySum: 9, reliabilityCount: 10, ...over,
});

const noop = () => {};

describe("ShortlistPanel", () => {
  it("renders nothing when closed", () => {
    render(<ShortlistPanel open={false} budget={30} shortlist={[opt({})]} nearMisses={[]}
      onSelect={noop} onClose={noop} />);
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("renders ranked cards, flags the best match, and fires onSelect", () => {
    const onSelect = vi.fn();
    render(<ShortlistPanel open budget={30} nearMisses={[]} onSelect={onSelect} onClose={noop}
      shortlist={[
        opt({ agentVersionId: "v1", agentName: "Alpha" }),
        opt({ agentVersionId: "v2", agentName: "Beta", price: 8 }),
      ]} />);
    expect(screen.getByText("Alpha")).toBeTruthy();
    expect(screen.getByText(/best match/i)).toBeTruthy();
    fireEvent.click(screen.getAllByRole("button", { name: "Select" })[0]);
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ agentVersionId: "v1" }));
  });

  it("renders the near-miss drawer with a pays-its-price button", () => {
    const onSelect = vi.fn();
    render(<ShortlistPanel open budget={20} shortlist={[]} onSelect={onSelect} onClose={noop}
      nearMisses={[opt({ agentVersionId: "v9", agentName: "Pricey", price: 25 })]} />);
    fireEvent.click(screen.getByRole("button", { name: /pays 25 cr/i }));
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ agentVersionId: "v9" }));
  });

  it("renders the agent's profile picture when logoUrl is present", () => {
    const { container } = render(<ShortlistPanel open budget={30} nearMisses={[]} onSelect={noop} onClose={noop}
      shortlist={[opt({ agentName: "Logo Co", logoUrl: "https://cdn.test/l.png" })]} />);
    expect(container.querySelector('img[src="https://cdn.test/l.png"]')).toBeTruthy();
  });

  it("shows each agent's public contract-decline rate", () => {
    render(<ShortlistPanel open budget={30} onSelect={noop} onClose={noop}
      shortlist={[opt({ agentVersionId: "v1", contractDeclines: { declined: 1, answered: 4, rate: 0.25 } })]}
      nearMisses={[opt({ agentVersionId: "v2", price: 40, contractDeclines: { declined: 0, answered: 0, rate: null } })]} />);
    expect(screen.getByText(/declined terms on/i)).toHaveTextContent("Declined terms on 1 of 4 tasks (25%)");
    expect(screen.getByText("No terms answered yet")).toBeInTheDocument();
  });

  it("shows the empty state when both lists are empty", () => {
    render(<ShortlistPanel open budget={30} shortlist={[]} nearMisses={[]} onSelect={noop} onClose={noop} />);
    expect(screen.getByText(/no agents match/i)).toBeTruthy();
  });

  it("says no agent matches the task closely when the relevance floor removed every agent", () => {
    render(<ShortlistPanel open budget={30} shortlist={[]} nearMisses={[]} noCloseMatch
      onSelect={noop} onClose={noop} />);
    expect(screen.getByRole("status")).toHaveTextContent(/no agent matches this task closely/i);
    expect(screen.queryByText(/no agents match this category/i)).toBeNull();
  });

  it("keeps the near-miss suggestions under the no-close-match message", () => {
    const onSelect = vi.fn();
    render(<ShortlistPanel open budget={20} shortlist={[]} noCloseMatch onSelect={onSelect} onClose={noop}
      nearMisses={[opt({ agentVersionId: "v9", agentName: "Pricey", price: 25 })]} />);
    expect(screen.getByRole("status")).toHaveTextContent(/no agent matches this task closely/i);
    fireEvent.click(screen.getByRole("button", { name: /pays 25 cr/i }));
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ agentVersionId: "v9" }));
  });

  it("fires onClose from the close button", () => {
    const onClose = vi.fn();
    render(<ShortlistPanel open budget={30} shortlist={[opt({})]} nearMisses={[]} onSelect={noop} onClose={onClose} />);
    fireEvent.click(screen.getByRole("button", { name: /close/i }));
    expect(onClose).toHaveBeenCalled();
  });
});

describe("ShortlistPanel — no-match explanation (#91)", () => {
  const noMatch = {
    summary: "s",
    exclusions: [
      { blocker: "PARAMETER" as const, parameter: "max_bytes", agents: 3 },
      { blocker: "RELEVANCE_FLOOR" as const, parameter: null, agents: 1 },
      { blocker: "TEMPLATE" as const, parameter: null, agents: 1 },
    ],
    suggestions: [{ parameter: "max_bytes", agents: 2 }],
  };

  it("names each blocking term with its agent count and offers the relax as the Client's choice", () => {
    const onRelax = vi.fn();
    render(<ShortlistPanel open budget={30} shortlist={[]} nearMisses={[]} onSelect={noop} onClose={noop}
      noMatch={noMatch} labelFor={(p) => (p === "max_bytes" ? "Largest file accepted" : p)} onRelax={onRelax} />);

    expect(screen.getByText(/no agent accepts these terms/i)).toBeTruthy();
    expect(screen.getByText("Largest file accepted")).toBeTruthy();
    expect(screen.getByText("3 agents")).toBeTruthy();
    expect(screen.getByText(/describe work like yours/i)).toBeTruthy();
    expect(screen.getByText(/deliver this kind of output/i)).toBeTruthy();
    expect(screen.queryByText(/no agents match this category/i)).toBeNull();
    expect(onRelax).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: /drop largest file accepted → 2 agents/i }));
    expect(onRelax).toHaveBeenCalledWith("max_bytes");
    expect(screen.getByText(/ruled out by your output terms/i)).toBeTruthy();
  });

  it("says the terms came from the description when they were extracted (#101)", () => {
    render(<ShortlistPanel open budget={30} shortlist={[]} nearMisses={[]} onSelect={noop} onClose={noop}
      noMatch={noMatch} termsExtracted />);

    expect(screen.getByText(/ruled out by the terms taken from your description/i)).toBeTruthy();
    expect(screen.queryByText(/your output terms/i)).toBeNull();
  });

  it("keeps the close-match copy when only the relevance floor ruled agents out", () => {
    render(<ShortlistPanel open budget={30} shortlist={[]} nearMisses={[]} onSelect={noop} onClose={noop}
      noCloseMatch noMatch={{ summary: "s", suggestions: [],
        exclusions: [{ blocker: "RELEVANCE_FLOOR", parameter: null, agents: 2 }] }} />);
    expect(screen.getByText(/no agent matches this task closely/i)).toBeTruthy();
    expect(screen.queryByText(/no agent accepts these terms/i)).toBeNull();
  });
});
