import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { ShortlistPanel } from "@/components/ShortlistPanel";
import type { AgentOptionDTO } from "@/lib/types";

const opt = (over: Partial<AgentOptionDTO>): AgentOptionDTO => ({
  agentId: "a", agentVersionId: "v", agentName: "Agent", tagline: null, logoUrl: null,
  price: 10, reputationScore: 80, availability: "AVAILABLE", outputFormat: "JSON",
  capabilityCategories: ["summarisation"], ...over,
});

describe("ShortlistPanel", () => {
  it("renders in-budget cards and fires onSelect on Select", () => {
    const onSelect = vi.fn();
    render(<ShortlistPanel budget={30} nearMisses={[]} onSelect={onSelect}
      shortlist={[opt({ agentVersionId: "v1", agentName: "Alpha" })]} />);
    expect(screen.getByText("Alpha")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Select" }));
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ agentVersionId: "v1" }));
  });

  it("renders the near-miss zone with an above-budget button", () => {
    const onSelect = vi.fn();
    render(<ShortlistPanel budget={20} shortlist={[]} onSelect={onSelect}
      nearMisses={[opt({ agentVersionId: "v2", agentName: "Pricey", price: 25 })]} />);
    expect(screen.getByText("Pricey")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /above budget/i }));
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ agentVersionId: "v2" }));
  });

  it("shows the empty state when both lists are empty", () => {
    render(<ShortlistPanel budget={30} shortlist={[]} nearMisses={[]} onSelect={vi.fn()} />);
    expect(screen.getByText(/no agents match/i)).toBeTruthy();
  });
});
