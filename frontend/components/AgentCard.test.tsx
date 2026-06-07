import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { AgentCard } from "@/components/AgentCard";
import { CategoryBar } from "@/components/CategoryBar";
import type { AgentCardDTO, CategoryCountDTO } from "@/lib/types";

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

const card: AgentCardDTO = {
  id: "ag-1",
  name: "Summariser Bot",
  builderName: "builder",
  tagline: "Fast, spec-true summaries",
  logoUrl: null,
  coverUrl: null,
  categories: ["summarisation"],
  price: 10,
  maxExecutionSeconds: 60,
  reputationScore: 60,
  ratingAvg: 4.5,
  ratingCount: 3,
  requestCount: 7,
  featured: true,
  createdAt: "2026-06-06T10:00:00Z",
};

describe("AgentCard", () => {
  it("renders name, builder, tagline, rating, price and links to the storefront", () => {
    render(<AgentCard agent={card} />);
    expect(screen.getByText("Summariser Bot")).toBeInTheDocument();
    expect(screen.getByText(/by builder/i)).toBeInTheDocument();
    expect(screen.getByText("Fast, spec-true summaries")).toBeInTheDocument();
    expect(screen.getByText("4.5")).toBeInTheDocument();
    expect(screen.getByText(/10\s*cr/i)).toBeInTheDocument();
    expect(screen.getByRole("link")).toHaveAttribute("href", "/client/agents/ag-1");
    expect(screen.getByText(/hot/i)).toBeInTheDocument(); // featured chip
  });

  it("shows a no-reviews state when ratingCount is 0", () => {
    render(<AgentCard agent={{ ...card, ratingAvg: null, ratingCount: 0, featured: false }} />);
    expect(screen.getByText(/no reviews yet/i)).toBeInTheDocument();
  });
});

// ── CategoryBar ──────────────────────────────────────────────────────────────

const cats: CategoryCountDTO[] = [
  { category: "summarisation", agentCount: 5 },
  { category: "coding", agentCount: 3 },
];

describe("CategoryBar", () => {
  it("renders an All chip and all category chips with counts", () => {
    render(<CategoryBar categories={cats} active="" onSelect={vi.fn()} />);
    expect(screen.getByText("All")).toBeInTheDocument();
    expect(screen.getByText("summarisation")).toBeInTheDocument();
    expect(screen.getByText("coding")).toBeInTheDocument();
    expect(screen.getByText("5")).toBeInTheDocument();
    expect(screen.getByText("3")).toBeInTheDocument();
  });

  it("calls onSelect with the category value when a chip is clicked", () => {
    const onSelect = vi.fn();
    render(<CategoryBar categories={cats} active="" onSelect={onSelect} />);
    fireEvent.click(screen.getByText("summarisation"));
    expect(onSelect).toHaveBeenCalledWith("summarisation");
  });

  it("calls onSelect with empty string when All is clicked", () => {
    const onSelect = vi.fn();
    render(<CategoryBar categories={cats} active="summarisation" onSelect={onSelect} />);
    fireEvent.click(screen.getByText("All"));
    expect(onSelect).toHaveBeenCalledWith("");
  });

  it("applies accent classes to the active chip", () => {
    render(<CategoryBar categories={cats} active="coding" onSelect={vi.fn()} />);
    const codingBtn = screen.getByRole("button", { name: /coding/ });
    expect(codingBtn.className).toContain("text-accent");
  });

  it("sets aria-pressed=true on the active chip and false on others", () => {
    render(<CategoryBar categories={cats} active="summarisation" onSelect={vi.fn()} />);
    const activeBtn = screen.getByRole("button", { name: /summarisation/ });
    const otherBtn = screen.getByRole("button", { name: /coding/ });
    const allBtn = screen.getByRole("button", { name: /^all$/i });
    expect(activeBtn).toHaveAttribute("aria-pressed", "true");
    expect(otherBtn).toHaveAttribute("aria-pressed", "false");
    expect(allBtn).toHaveAttribute("aria-pressed", "false");
  });
});
