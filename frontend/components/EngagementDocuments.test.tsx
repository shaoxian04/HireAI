import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { EngagementDocuments, downloadSummary } from "@/components/EngagementDocuments";
import type { EngagementDTO, SourceDTO } from "@/lib/types";

const api = vi.fn();
const apiUpload = vi.fn();
vi.mock("@/lib/api", async (orig) => ({
  ...(await orig<typeof import("@/lib/api")>()),
  api: (path: string, init?: RequestInit) => api(path, init),
  apiUpload: (path: string, form: FormData) => apiUpload(path, form),
}));

const ACTIVE: EngagementDTO = {
  id: "en-1", clientId: "c-1", agentId: "ag-1", status: "ACTIVE", consentedAt: "2026-11-16T09:00:00Z",
  retentionDays: 30, createdAt: "2026-11-16T09:00:00Z",
};

function doc(id: string, extra: Partial<SourceDTO> = {}): SourceDTO {
  return {
    id, engagementId: "en-1", filename: `${id}.pdf`, mediaType: "application/pdf", sizeBytes: 2048,
    sha256: "ab".repeat(32), uploadedAt: "2026-11-16T09:30:00Z", downloads: [], ...extra,
  };
}

beforeEach(() => {
  api.mockReset();
  apiUpload.mockReset();
});

describe("EngagementDocuments", () => {
  it("offers to start an Engagement and needs the buyer's consent first", async () => {
    api.mockResolvedValueOnce([]); // no Engagement yet
    api.mockResolvedValueOnce({ ...ACTIVE, retentionDays: 7 });
    const onChange = vi.fn();
    render(<EngagementDocuments agentId="ag-1" agentName="Invoice Bot" onChange={onChange} />);

    const start = await screen.findByRole("button", { name: "Start an Engagement" });
    expect(start).toBeDisabled();
    await userEvent.click(screen.getByLabelText(/I consent to sending my confidential documents to Invoice Bot/));
    await userEvent.selectOptions(screen.getByLabelText("Keep documents for"), "7");
    await userEvent.click(start);

    expect(api).toHaveBeenLastCalledWith("/engagements", expect.objectContaining({ method: "POST" }));
    expect(JSON.parse(api.mock.calls[1][1].body)).toEqual({ agentId: "ag-1", confidentialConsent: true, retentionDays: 7 });
    expect(await screen.findByText("Engagement active")).toBeInTheDocument();
    expect(screen.getByText(/kept 7 days after the last window closes/)).toBeInTheDocument();
    expect(onChange).toHaveBeenLastCalledWith({ engagementId: "en-1", sourceIds: [] });
  });

  it("selects every uploaded document as an Item, in order, and shows its hash and downloads", async () => {
    api.mockResolvedValueOnce([ACTIVE]);
    api.mockResolvedValueOnce([doc("old", {
      downloads: [{ at: "2026-11-16T10:00:00Z", taskId: "t-1", itemNo: 1, outcome: "SERVED" }],
    })]);
    apiUpload.mockResolvedValueOnce(doc("a")).mockResolvedValueOnce(doc("b"));
    const onChange = vi.fn();
    render(<EngagementDocuments agentId="ag-1" agentName="Invoice Bot" onChange={onChange} />);

    expect(await screen.findByText("old.pdf")).toBeInTheDocument();
    expect(screen.getByText(/Downloaded through its link 1×/)).toBeInTheDocument();
    await userEvent.upload(screen.getByLabelText("Documents to upload"), [
      new File(["%PDF a"], "a.pdf", { type: "application/pdf" }),
      new File(["%PDF b"], "b.pdf", { type: "application/pdf" }),
    ]);

    await waitFor(() => expect(onChange).toHaveBeenLastCalledWith({ engagementId: "en-1", sourceIds: ["a", "b"] }));
    expect(apiUpload).toHaveBeenCalledWith("/engagements/en-1/sources", expect.any(FormData));
    expect(screen.getByText("Item #02")).toBeInTheDocument();

    await userEvent.click(screen.getByLabelText("Send a.pdf as an Item"));
    expect(onChange).toHaveBeenLastCalledWith({ engagementId: "en-1", sourceIds: ["b"] });
  });

  it("says when the Engagement waits for the agent, and a retired document cannot be chosen", async () => {
    api.mockResolvedValueOnce([{ ...ACTIVE, status: "AWAITING_AGENT_ACCEPTANCE" }]);
    const { unmount } = render(<EngagementDocuments agentId="ag-1" agentName="Invoice Bot" onChange={vi.fn()} />);
    expect(await screen.findByText(/waiting for Invoice Bot's builder to accept/)).toBeInTheDocument();
    unmount();

    api.mockResolvedValueOnce([ACTIVE]);
    api.mockResolvedValueOnce([doc("gone", { contentDeletedAt: "2026-12-20T00:00:00Z" })]);
    render(<EngagementDocuments agentId="ag-1" agentName="Invoice Bot" onChange={vi.fn()} />);
    expect(await screen.findByLabelText("Send gone.pdf as an Item")).toBeDisabled();
    expect(screen.getByText(/only the hash is kept/)).toBeInTheDocument();
  });

  it("shows a failed upload's reason without losing the others", async () => {
    api.mockResolvedValueOnce([ACTIVE]).mockResolvedValueOnce([]);
    const { ApiError } = await import("@/lib/api");
    apiUpload.mockRejectedValueOnce(new ApiError("VALIDATION_ERROR", "file: the document is empty", 400));
    render(<EngagementDocuments agentId="ag-1" agentName="Invoice Bot" onChange={vi.fn()} />);

    await userEvent.upload(await screen.findByLabelText("Documents to upload"), new File([""], "empty.pdf"));

    expect(await screen.findByRole("alert")).toHaveTextContent("empty.pdf: file: the document is empty");
  });
});

describe("downloadSummary", () => {
  it("counts served downloads and refused (expired) attempts", () => {
    expect(downloadSummary(doc("x"))).toBe("Not downloaded yet");
    expect(downloadSummary(doc("x", {
      downloads: [{ at: "2026-11-16T10:00:00Z", taskId: "t", itemNo: 1, outcome: "EXPIRED" }],
    }))).toBe("Not downloaded yet · 1 refused (link expired)");
  });
});
