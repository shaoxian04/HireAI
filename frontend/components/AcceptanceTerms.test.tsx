import { render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ContractPanel, OfferPanel } from "./AcceptanceTerms";
import type { TemplateDTO } from "@/lib/types";

const SCHEMA = '{"type":"object"}';

const TEMPLATES: TemplateDTO[] = [
  {
    key: "custom_json",
    version: 1,
    title: "JSON with your schema",
    description: "A JSON deliverable. Accepted automatically when it parses.",
    outputShape: "JSON",
    checks: ["FORMAT_JSON_PARSEABLE", "SCHEMA_MATCH"],
    parameters: [
      { name: "schema", label: "JSON Schema", description: null, comparison: "enum", bounds: { maxLength: 5000 } },
    ],
    expectationMode: "NONE",
    rulingSettlement: { FULFILLED: 1, PARTIALLY_FULFILLED: 0.5, NOT_FULFILLED: 0 },
    status: "ACTIVE",
    publishedAt: "2026-11-15T00:00:00Z",
  },
  {
    key: "file_basic",
    version: 1,
    title: "File",
    description: "A file delivered by HTTPS link.",
    outputShape: "FILE",
    checks: ["FORMAT_FILE_HTTPS_URL"],
    parameters: [
      { name: "max_bytes", label: "Largest file size", description: null, comparison: "min",
        bounds: { minimum: 1, maximum: 104857600 } },
      { name: "media_types", label: "Accepted file types", description: null, comparison: "subset",
        bounds: { allowedValues: ["application/pdf", "image/png"] } },
    ],
    expectationMode: "NONE",
    rulingSettlement: { FULFILLED: 1, PARTIALLY_FULFILLED: 0.5, NOT_FULFILLED: 0 },
    status: "ACTIVE",
    publishedAt: "2026-11-15T00:00:00Z",
  },
];

const apiMock = vi.fn();
vi.mock("@/lib/api", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api")>("@/lib/api");
  return { ...actual, api: (path: string) => apiMock(path) };
});

describe("ContractPanel", () => {
  beforeEach(() => {
    apiMock.mockReset();
    apiMock.mockResolvedValue(TEMPLATES);
  });

  it("names the Template in plain words and lists the frozen parameters", async () => {
    render(<ContractPanel contract={{ template: "custom_json", version: 1, params: { schema: SCHEMA } }} />);

    expect(await screen.findByText("JSON with your schema")).toBeInTheDocument();
    expect(screen.getByText("custom_json@1")).toBeInTheDocument();
    expect(screen.getByText("JSON Schema")).toBeInTheDocument();
    expect(screen.getByText(SCHEMA)).toBeInTheDocument();
    expect(apiMock).toHaveBeenCalledWith("/catalogue/templates");
  });

  it("says when the Contract sets no parameters", async () => {
    render(<ContractPanel contract={{ template: "file_basic", version: 1, params: {} }} />);

    expect(await screen.findByText(/no parameters are set/i)).toBeInTheDocument();
  });

  it("shows a loading state, then still names the Template when its details can't load", async () => {
    apiMock.mockRejectedValue(new Error("down"));
    render(<ContractPanel contract={{ template: "custom_json", version: 1, params: { schema: SCHEMA } }} />);

    expect(screen.getByRole("status")).toHaveTextContent(/loading the contract/i);
    expect(await screen.findByText(/couldn.t load what this template means/i)).toBeInTheDocument();
    expect(screen.getByText("custom_json@1")).toBeInTheDocument();
    expect(screen.getByText(SCHEMA)).toBeInTheDocument();
  });
});

describe("OfferPanel", () => {
  beforeEach(() => {
    apiMock.mockReset();
    apiMock.mockResolvedValue(TEMPLATES);
  });

  it("shows the price and the only schema a migrated JSON Offer accepts", async () => {
    render(
      <OfferPanel
        offer={{ price: 12, terms: [{ template: "custom_json", version: 1, params: { schema: { oneOf: [SCHEMA] } } }] }}
      />,
    );

    const term = await screen.findByRole("listitem");
    expect(within(term).getByText("JSON with your schema")).toBeInTheDocument();
    expect(within(term).getByText(/only this value/i)).toBeInTheDocument();
    expect(within(term).getByText(SCHEMA)).toBeInTheDocument();
    expect(screen.getByText(/12 credits/)).toBeInTheDocument();
  });

  it("lists the parameters an Offer does not name as accepted only when left unset", async () => {
    render(<OfferPanel offer={{ price: 5, terms: [{ template: "file_basic", version: 1, params: {} }] }} />);

    expect(await screen.findByText(/largest file size, accepted file types/i)).toHaveTextContent(/leaves them unset/i);
  });

  it("describes bounds and open ranges", async () => {
    render(
      <OfferPanel
        offer={{
          price: 5,
          terms: [{ template: "file_basic", version: 1,
            params: { max_bytes: { bound: 1048576 }, media_types: { any: true } } }],
        }}
      />,
    );

    expect(await screen.findByText("1 MB or more")).toBeInTheDocument();
    expect(screen.getByText("Any value")).toBeInTheDocument();
  });

  it("says so when there is no Offer to show", () => {
    render(<OfferPanel offer={undefined} />);

    expect(screen.getByText(/no offer/i)).toBeInTheDocument();
  });
});
