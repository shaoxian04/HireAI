import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { DeliverablePanel, LegacyDeliverableLink, formatBytes } from "@/components/DeliverablePanel";
import type { DeliverableDTO } from "@/lib/types";

const apiBlob = vi.fn();
vi.mock("@/lib/api", async (orig) => ({
  ...(await orig<typeof import("@/lib/api")>()),
  apiBlob: (path: string) => apiBlob(path),
}));

const PNG: DeliverableDTO = {
  mediaType: "image/png",
  declaredMediaType: "image/png",
  sizeBytes: 2048,
  sha256: "ab".repeat(32),
  fetchedAt: "2026-11-15T10:00:00Z",
};

beforeEach(() => {
  apiBlob.mockReset();
  URL.createObjectURL = vi.fn(() => "blob:preview");
  URL.revokeObjectURL = vi.fn();
});

describe("DeliverablePanel", () => {
  it("previews an image from the platform's frozen copy, never the agent's link", async () => {
    apiBlob.mockResolvedValue(new Blob(["x"], { type: "image/png" }));
    render(
      <DeliverablePanel deliverable={PNG} sourceUrl="https://agent.example/a.png" downloadPath="/tasks/t-1/deliverable" fileStem="d" />,
    );

    const img = await screen.findByRole("img", { name: /preview of the delivered file/i });
    expect(img).toHaveAttribute("src", "blob:preview");
    expect(apiBlob).toHaveBeenCalledWith("/tasks/t-1/deliverable");
    expect(screen.getByText("PNG image")).toBeInTheDocument();
    expect(screen.getByText("2.0 KB")).toBeInTheDocument();
    expect(screen.getByText("ab".repeat(32))).toBeInTheDocument();
    // The source is shown as text, not as a link to follow.
    expect(screen.getByText("https://agent.example/a.png")).toBeInTheDocument();
    expect(screen.queryByRole("link")).toBeNull();
  });

  it("says when the agent's label disagreed with the bytes", () => {
    render(
      <DeliverablePanel
        deliverable={{ ...PNG, mediaType: "text/html", declaredMediaType: "image/png" }}
        sourceUrl={null}
        downloadPath="/tasks/t-1/deliverable"
        fileStem="d"
      />,
    );
    expect(screen.getByText("HTML page")).toBeInTheDocument();
    expect(screen.getByText(/labelled it image\/png/i)).toBeInTheDocument();
    expect(apiBlob).not.toHaveBeenCalled(); // no preview for non-images
  });

  it("shows a retryable error when the preview cannot load", async () => {
    apiBlob.mockRejectedValueOnce(new Error("offline")).mockResolvedValue(new Blob(["x"]));
    render(<DeliverablePanel deliverable={PNG} sourceUrl={null} downloadPath="/p" fileStem="d" />);

    expect(await screen.findByRole("alert")).toHaveTextContent(/couldn't load the file/i);
    await userEvent.click(screen.getByRole("button", { name: /try again/i }));
    expect(await screen.findByRole("img")).toBeInTheDocument();
  });

  it("downloads the frozen bytes", async () => {
    apiBlob.mockResolvedValue(new Blob(["x"]));
    render(
      <DeliverablePanel
        deliverable={{ ...PNG, mediaType: "text/csv", declaredMediaType: "text/csv" }}
        sourceUrl={null}
        downloadPath="/tasks/t-1/deliverable"
        fileStem="deliverable-t-1"
      />,
    );
    await userEvent.click(screen.getByRole("button", { name: /download file/i }));
    await waitFor(() => expect(apiBlob).toHaveBeenCalledWith("/tasks/t-1/deliverable"));
    expect(URL.createObjectURL).toHaveBeenCalled();
  });
});

describe("LegacyDeliverableLink", () => {
  it("labels a link-only delivery as the agent's link", () => {
    render(<LegacyDeliverableLink url="https://agent.example/a.pdf" />);
    expect(screen.getByRole("link", { name: /open the agent's link/i })).toHaveAttribute(
      "href",
      "https://agent.example/a.pdf",
    );
  });
});

describe("formatBytes", () => {
  it("reads sizes in words", () => {
    expect(formatBytes(1)).toBe("1 byte");
    expect(formatBytes(512)).toBe("512 bytes");
    expect(formatBytes(1536)).toBe("1.5 KB");
    expect(formatBytes(5 * 1024 * 1024)).toBe("5.0 MB");
  });
});
