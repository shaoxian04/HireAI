import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { TabPricing } from "@/components/manage/TabPricing";
import type { AgentDTO } from "@/lib/types";

/** Records only the publish call; the category list lookup gets two buckets. */
const apiMock = vi.fn();
vi.mock("@/lib/api", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api")>("@/lib/api");
  return {
    ...actual,
    api: (path: string, init?: RequestInit) =>
      path.startsWith("/catalogue/")
        ? Promise.resolve([
            { category: "summarisation", label: "Summarisation", agentCount: 1 },
            { category: "other", label: "Other", agentCount: 0 },
          ])
        : apiMock(path, init),
  };
});

const AGENT: AgentDTO = {
  id: "a-1",
  ownerId: "u-1",
  name: "Bot",
  status: "ACTIVE",
  currentVersionId: "v-1",
  reputationScore: 80,
  createdAt: "2026-11-01T00:00:00Z",
  hasSigningSecret: true,
  currentVersion: {
    outputSpec: { format: "JSON", schema: "{}", acceptanceCriteria: "ok" },
    capabilityCategories: ["summarisation"],
    webhookUrl: "https://a.example/run",
    maxExecutionSeconds: 60,
    price: 10,
  },
};

describe("TabPricing — execution time limit", () => {
  beforeEach(() => apiMock.mockReset());

  it("shows the 24-hour execution limit", () => {
    render(<TabPricing agentId="a-1" agent={AGENT} onAgentChange={() => {}} />);
    expect(screen.getByText(/86,400 seconds \(24 hours\)/)).toBeInTheDocument();
  });

  it("stops a max execution time above 24 hours before it is published", async () => {
    render(<TabPricing agentId="a-1" agent={AGENT} onAgentChange={() => {}} />);
    fireEvent.change(screen.getByLabelText(/max execution seconds/i), { target: { value: "86401" } });
    fireEvent.click(screen.getByRole("button", { name: /publish version/i }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/at most 86400/i);
    expect(apiMock).not.toHaveBeenCalled();
  });

  it("publishes exactly 24 hours", async () => {
    apiMock.mockResolvedValue(AGENT);
    render(<TabPricing agentId="a-1" agent={AGENT} onAgentChange={() => {}} />);
    fireEvent.change(screen.getByLabelText(/max execution seconds/i), { target: { value: "86400" } });
    fireEvent.click(screen.getByRole("button", { name: /publish version/i }));
    await waitFor(() => expect(apiMock).toHaveBeenCalledTimes(1));
    const body = JSON.parse((apiMock.mock.calls[0][1] as RequestInit).body as string);
    expect(body.maxExecutionSeconds).toBe(86400);
  });
});

describe("TabPricing — category list", () => {
  beforeEach(() => apiMock.mockReset());

  it("publishes the picked categories and a suggested category", async () => {
    apiMock.mockResolvedValue(AGENT);
    render(<TabPricing agentId="a-1" agent={AGENT} onAgentChange={() => {}} />);
    expect(await screen.findByRole("checkbox", { name: "Summarisation" })).toBeChecked();
    fireEvent.click(screen.getByRole("checkbox", { name: "Other" }));
    fireEvent.change(screen.getByLabelText(/suggest a category/i), { target: { value: "Contract redlining" } });
    fireEvent.click(screen.getByRole("button", { name: /publish version/i }));
    await waitFor(() => expect(apiMock).toHaveBeenCalledTimes(1));
    const body = JSON.parse((apiMock.mock.calls[0][1] as RequestInit).body as string);
    expect(body.capabilityCategories).toEqual(["summarisation", "other"]);
    expect(body.suggestedCategory).toBe("Contract redlining");
  });
});
