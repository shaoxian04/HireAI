import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { ShortlistPanel } from "@/components/ShortlistPanel";
import type { AgentOptionDTO } from "@/lib/types";

const opt = (over: Partial<AgentOptionDTO>): AgentOptionDTO => ({
  agentId: "a", agentVersionId: "v", agentName: "Agent", tagline: null, logoUrl: null,
  price: 10, reputationScore: 80, availability: "AVAILABLE", outputFormat: "JSON",
  capabilityCategories: ["summarisation"], ...over,
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

  it("shows the empty state when both lists are empty", () => {
    render(<ShortlistPanel open budget={30} shortlist={[]} nearMisses={[]} onSelect={noop} onClose={noop} />);
    expect(screen.getByText(/no agents match/i)).toBeTruthy();
  });

  it("fires onClose from the close button", () => {
    const onClose = vi.fn();
    render(<ShortlistPanel open budget={30} shortlist={[opt({})]} nearMisses={[]} onSelect={noop} onClose={onClose} />);
    fireEvent.click(screen.getByRole("button", { name: /close/i }));
    expect(onClose).toHaveBeenCalled();
  });
});
