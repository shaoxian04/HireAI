import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import { TabPricing } from "@/components/manage/TabPricing";
import type { AgentDTO, TemplateDTO } from "@/lib/types";
import { ApiError } from "@/lib/api";

/** Records only the publish call; the category list lookup gets two buckets. */
const apiMock = vi.fn();
vi.mock("@/lib/api", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api")>("@/lib/api");
  return {
    ...actual,
    api: (path: string, init?: RequestInit) =>
      path === "/catalogue/templates"
        ? templatesMock()
        : path.startsWith("/catalogue/")
          ? Promise.resolve([
              { category: "summarisation", label: "Summarisation", agentCount: 1 },
              { category: "other", label: "Other", agentCount: 0 },
            ])
          : apiMock(path, init),
  };
});

const SETTLEMENT = { FULFILLED: 1, PARTIALLY_FULFILLED: 0.5, NOT_FULFILLED: 0 };
const TEMPLATES: TemplateDTO[] = [
  {
    key: "custom_json", version: 1, title: "JSON with your schema", description: "A JSON deliverable.",
    outputShape: "JSON", checks: [], expectationMode: "NONE", rulingSettlement: SETTLEMENT,
    status: "ACTIVE", publishedAt: "2026-11-15T00:00:00Z",
    parameters: [{ name: "schema", label: "JSON Schema", description: null, comparison: "enum", bounds: { maxLength: 5000 } }],
  },
  {
    key: "file_basic", version: 1, title: "File", description: "A file delivered by HTTPS link.",
    outputShape: "FILE", checks: [], expectationMode: "NONE", rulingSettlement: SETTLEMENT,
    status: "ACTIVE", publishedAt: "2026-11-15T00:00:00Z",
    parameters: [
      { name: "max_bytes", label: "Largest file size", description: null, comparison: "min",
        bounds: { minimum: 1, maximum: 104857600 } },
      { name: "media_types", label: "Accepted file types", description: null, comparison: "subset",
        bounds: { allowedValues: ["application/pdf", "image/png"] } },
    ],
  },
];
const templatesMock = vi.fn(() => Promise.resolve(TEMPLATES));

const AGENT: AgentDTO = {
  id: "a-1",
  ownerId: "u-1",
  name: "Bot",
  status: "ACTIVE",
  currentVersionId: "v-1",
  reputationScore: 80,
  createdAt: "2026-11-01T00:00:00Z",
  hasSigningSecret: true,
  listed: true,
  currentVersion: {
    outputSpec: { format: "JSON", schema: "{}", acceptanceCriteria: "ok" },
    offer: { price: 10, terms: [{ template: "custom_json", version: 1, params: { schema: { oneOf: ["{}"] } } }] },
    capabilityCategories: ["summarisation"],
    webhookUrl: "https://a.example/run",
    maxExecutionSeconds: 60,
    price: 10,
  },
};

describe("TabPricing — execution time limit", () => {
  beforeEach(() => apiMock.mockReset());

  it("shows the version's Offer beside its output contract", async () => {
    render(<TabPricing agentId="a-1" agent={AGENT} onAgentChange={() => {}} />);
    expect(screen.getByRole("heading", { name: "Offer" })).toBeInTheDocument();
    expect(await screen.findByText("custom_json@1")).toBeInTheDocument();
    expect(screen.getByText(/only this value/i)).toBeInTheDocument();
  });

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

describe("TabPricing — Offer editor", () => {
  beforeEach(() => {
    apiMock.mockReset();
    templatesMock.mockReset();
    templatesMock.mockImplementation(() => Promise.resolve(TEMPLATES));
  });

  function bodyOfPublish() {
    return JSON.parse((apiMock.mock.calls[0][1] as RequestInit).body as string);
  }

  it("publishes without an offer when the Offer was not edited, so the current one carries over", async () => {
    apiMock.mockResolvedValue(AGENT);
    render(<TabPricing agentId="a-1" agent={AGENT} onAgentChange={() => {}} />);
    fireEvent.click(screen.getByRole("button", { name: /publish version/i }));
    await waitFor(() => expect(apiMock).toHaveBeenCalledTimes(1));
    expect(bodyOfPublish()).not.toHaveProperty("offer");
  });

  it("widens the schema, adds a Template with ranges and publishes the edited Offer", async () => {
    apiMock.mockResolvedValue(AGENT);
    render(<TabPricing agentId="a-1" agent={AGENT} onAgentChange={() => {}} />);
    fireEvent.click(screen.getByRole("button", { name: /edit offer/i }));

    const json = await screen.findByRole("group", { name: /JSON with your schema/ });
    // The Agent's own Template stays: direct bookings are judged under it.
    expect(within(json).getByRole("checkbox", { name: /accept/i })).toBeChecked();
    expect(within(json).getByRole("checkbox", { name: /accept/i })).toBeDisabled();
    fireEvent.click(within(json).getByRole("radio", { name: /any value/i }));

    const file = screen.getByRole("group", { name: /^File/ });
    fireEvent.click(within(file).getByRole("checkbox", { name: /accept/i }));
    fireEvent.click(within(file).getByRole("radio", { name: /from a limit/i }));
    fireEvent.change(within(file).getByLabelText(/largest file size limit/i), { target: { value: "10485760" } });
    expect(within(file).getByText(/10 MB/)).toBeInTheDocument();
    fireEvent.click(within(file).getByRole("radio", { name: /only these/i }));
    fireEvent.click(within(file).getByRole("checkbox", { name: "application/pdf" }));

    fireEvent.click(screen.getByRole("button", { name: /publish version/i }));
    await waitFor(() => expect(apiMock).toHaveBeenCalledTimes(1));
    expect(bodyOfPublish().offer).toEqual({
      terms: [
        { template: "custom_json", version: 1, params: { schema: { any: true } } },
        {
          template: "file_basic",
          version: 1,
          params: { max_bytes: { bound: 10485760 }, media_types: { oneOf: ["application/pdf"] } },
        },
      ],
      acceptsAiReview: false, // not ticked: the Offer keeps refusing AI Review
    });
  });

  it("publishes the Builder's consent to AI Review with the edited Offer", async () => {
    apiMock.mockResolvedValue(AGENT);
    render(<TabPricing agentId="a-1" agent={AGENT} onAgentChange={() => {}} />);
    expect(screen.getByText(/Doesn't accept AI Review/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /edit offer/i }));
    await screen.findByRole("group", { name: /JSON with your schema/ });

    fireEvent.click(screen.getByRole("checkbox", { name: /Accept AI Review/ }));
    fireEvent.click(screen.getByRole("button", { name: /publish version/i }));
    await waitFor(() => expect(apiMock).toHaveBeenCalledTimes(1));
    expect(bodyOfPublish().offer.acceptsAiReview).toBe(true);
  });

  it("keeps the Agent's own schema in a narrowed list", async () => {
    apiMock.mockResolvedValue(AGENT);
    render(<TabPricing agentId="a-1" agent={AGENT} onAgentChange={() => {}} />);
    fireEvent.click(screen.getByRole("button", { name: /edit offer/i }));
    const json = await screen.findByRole("group", { name: /JSON with your schema/ });
    // Leaving the schema unset would drop the Agent's own schema, so it is not offered.
    expect(within(json).queryByRole("radio", { name: /leaves it unset/i })).not.toBeInTheDocument();
    expect(within(json).getByRole("radio", { name: /only these/i })).toBeChecked();
    fireEvent.click(within(json).getByRole("button", { name: /add another/i }));
    fireEvent.change(within(json).getByRole("textbox", { name: "JSON Schema 2" }), { target: { value: '{"type":"array"}' } });

    fireEvent.click(screen.getByRole("button", { name: /publish version/i }));
    await waitFor(() => expect(apiMock).toHaveBeenCalledTimes(1));
    expect(bodyOfPublish().offer.terms[0].params.schema).toEqual({ oneOf: ["{}", '{"type":"array"}'] });
  });

  it("stops a limit outside the Template's bounds before publishing", async () => {
    render(<TabPricing agentId="a-1" agent={AGENT} onAgentChange={() => {}} />);
    fireEvent.click(screen.getByRole("button", { name: /edit offer/i }));
    const file = await screen.findByRole("group", { name: /^File/ });
    fireEvent.click(within(file).getByRole("checkbox", { name: /accept/i }));
    fireEvent.click(within(file).getByRole("radio", { name: /from a limit/i }));
    fireEvent.change(within(file).getByLabelText(/largest file size limit/i), { target: { value: "0" } });

    fireEvent.click(screen.getByRole("button", { name: /publish version/i }));
    expect(await within(file).findByText(/between 1 and 104,857,600/)).toBeInTheDocument();
    expect(screen.getByText(/fix the offer/i)).toBeInTheDocument();
    expect(apiMock).not.toHaveBeenCalled();
  });

  it("shows the server's reason when the Offer is refused", async () => {
    apiMock.mockRejectedValue(
      new ApiError(
        "VALIDATION_ERROR",
        "Offer for file_basic@1: Largest file size (max_bytes) must be between 1 and 104857600; got 0",
        400,
      ),
    );
    render(<TabPricing agentId="a-1" agent={AGENT} onAgentChange={() => {}} />);
    fireEvent.click(screen.getByRole("button", { name: /edit offer/i }));
    await screen.findByRole("group", { name: /JSON with your schema/ });
    fireEvent.click(screen.getByRole("button", { name: /publish version/i }));
    expect(await screen.findByText(/must be between 1 and 104857600; got 0/)).toBeInTheDocument();
  });

  it("can't edit the Offer while the Templates fail to load, and can go back to the current one", async () => {
    templatesMock.mockImplementation(() => Promise.reject(new Error("down")));
    render(<TabPricing agentId="a-1" agent={AGENT} onAgentChange={() => {}} />);
    fireEvent.click(screen.getByRole("button", { name: /edit offer/i }));
    expect(await screen.findByText(/couldn.t load the templates/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /keep the current offer/i }));
    expect(screen.getByRole("button", { name: /edit offer/i })).toBeInTheDocument();
  });
});
