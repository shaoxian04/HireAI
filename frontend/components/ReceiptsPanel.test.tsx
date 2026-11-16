import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ReceiptsPanel } from "@/components/ReceiptsPanel";
import type { ReceiptDTO } from "@/lib/types";

const api = vi.fn();
const apiBlob = vi.fn();
vi.mock("@/lib/api", async (orig) => ({
  ...(await orig<typeof import("@/lib/api")>()),
  api: (path: string) => api(path),
  apiBlob: (path: string) => apiBlob(path),
}));

function receipt(itemNo: number | null, outcome: "ACCEPT" | "REJECT" | "SPLIT", paid: string, refunded: string): ReceiptDTO {
  return {
    id: `r-${itemNo ?? 0}`,
    taskId: "t-1",
    itemNo,
    keyId: "ed25519-0123456789abcdef",
    issuedAt: "2026-11-16T08:00:00Z",
    receipt: {
      settlement: { outcome, price: "10.00", paid, commission: "1.50", refunded, settledAt: "2026-11-16T08:00:00Z" },
    },
    signature: "sig",
  };
}

beforeEach(() => {
  api.mockReset();
  apiBlob.mockReset();
  URL.createObjectURL = vi.fn(() => "blob:receipt");
  URL.revokeObjectURL = vi.fn();
});

describe("ReceiptsPanel", () => {
  it("lists the task's receipt with what it settled and downloads the signed document", async () => {
    api.mockResolvedValue([receipt(null, "ACCEPT", "8.50", "0.00")]);
    apiBlob.mockResolvedValue(new Blob(["{}"], { type: "application/json" }));
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});

    render(<ReceiptsPanel taskId="t-1" refreshKey="RESOLVED" />);

    expect(await screen.findByText(/8\.50 cr paid to the builder/i)).toBeInTheDocument();
    expect(api).toHaveBeenCalledWith("/tasks/t-1/receipts");
    expect(screen.getByRole("heading", { name: /signed receipt/i })).toBeInTheDocument();
    expect(screen.getByText("ed25519-0123456789abcdef")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /download receipt/i }));
    await waitFor(() => expect(apiBlob).toHaveBeenCalledWith("/receipts/r-0"));
    expect(click).toHaveBeenCalled();
    click.mockRestore();
  });

  it("names each Item of a multi-Item task and how it settled", async () => {
    api.mockResolvedValue([receipt(1, "ACCEPT", "2.55", "0.00"), receipt(2, "REJECT", "0.00", "3.00")]);

    render(<ReceiptsPanel taskId="t-1" refreshKey="k" />);

    expect(await screen.findByText("Item 1")).toBeInTheDocument();
    expect(screen.getByText("Item 2")).toBeInTheDocument();
    expect(screen.getByText(/3\.00 cr refunded/i)).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: /download receipt/i })).toHaveLength(2);
    // The sentence joins the explanation with a space, not "own.Anyone".
    expect(screen.getByRole("region", { name: /signed receipts/i })).toHaveTextContent(/its own\. Anyone/);
  });

  it("describes a split", async () => {
    api.mockResolvedValue([receipt(null, "SPLIT", "4.25", "5.00")]);
    render(<ReceiptsPanel taskId="t-1" refreshKey="k" />);
    expect(await screen.findByText(/4\.25 cr paid to the builder, 5\.00 cr refunded/i)).toBeInTheDocument();
  });

  it("says when no receipt is issued yet", async () => {
    api.mockResolvedValue([]);
    render(<ReceiptsPanel taskId="t-1" refreshKey="k" />);
    expect(await screen.findByText(/no receipt yet/i)).toBeInTheDocument();
  });

  it("shows a retryable error", async () => {
    api.mockRejectedValueOnce(new Error("offline")).mockResolvedValue([receipt(null, "REJECT", "0.00", "10.00")]);
    render(<ReceiptsPanel taskId="t-1" refreshKey="k" />);

    expect(await screen.findByRole("alert")).toHaveTextContent(/couldn.t load the receipts/i);
    await userEvent.click(screen.getByRole("button", { name: /try again/i }));
    expect(await screen.findByText(/10\.00 cr refunded/i)).toBeInTheDocument();
  });
});
