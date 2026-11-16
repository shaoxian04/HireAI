import { render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AiReviewReport } from "./AiReviewReport";
import type { AiReviewDTO } from "@/lib/types";

const apiMock = vi.fn();
vi.mock("@/lib/api", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api")>("@/lib/api");
  return { ...actual, api: (path: string) => apiMock(path) };
});

const FROZEN = { criteria: ["Polite tone", "Offers a refund"], fee: 0.3 };

function review(over: Partial<AiReviewDTO>): AiReviewDTO {
  return {
    status: "COMPLETED",
    decisive: false,
    fee: 0.3,
    criteria: FROZEN.criteria,
    verdicts: [
      { criterion: "Polite tone", passed: true, reason: "Warm and apologetic throughout." },
      { criterion: "Offers a refund", passed: false, reason: "No refund is mentioned." },
    ],
    rulingCategory: "PARTIALLY_FULFILLED",
    model: "gpt-4o",
    attempts: 1,
    requestedAt: "2026-11-16T10:00:00Z",
    completedAt: "2026-11-16T10:00:20Z",
    ...over,
  };
}

describe("AiReviewReport", () => {
  beforeEach(() => {
    apiMock.mockReset();
  });

  it("shows each criterion's verdict and reason as advice on the web", async () => {
    apiMock.mockResolvedValue(review({}));
    render(<AiReviewReport taskId="t1" taskStatus="PENDING_REVIEW" frozen={FROZEN} />);

    expect(await screen.findByText("AI pre-review report")).toBeInTheDocument();
    expect(screen.getByText(/1 of 2 criteria met\. This is advice for your review: you still decide/)).toBeInTheDocument();
    expect(screen.getByText("No refund is mentioned.")).toBeInTheDocument();
    const items = screen.getAllByRole("listitem");
    expect(within(items[0]).getByText(/Met:/)).toBeInTheDocument();
    expect(within(items[1]).getByText(/Not met:/)).toBeInTheDocument();
    expect(screen.getByText("AI Review: Some met.")).toBeInTheDocument();
    expect(screen.getByText(/Fee 0\.30 cr, charged for the review, by gpt-4o/)).toBeInTheDocument();
    expect(apiMock).toHaveBeenCalledWith("/tasks/t1/ai-review");
  });

  it("says a decisive verdict settled the task and is final", async () => {
    apiMock.mockResolvedValue(review({ decisive: true, rulingCategory: "FULFILLED",
      verdicts: [{ criterion: "Polite tone", passed: true, reason: "ok" }, { criterion: "Offers a refund", passed: true, reason: "ok" }] }));
    render(<AiReviewReport taskId="t1" taskStatus="RESOLVED" frozen={FROZEN} />);

    expect(await screen.findByText("AI Review verdict")).toBeInTheDocument();
    expect(screen.getByText(/2 of 2 criteria met\. You booked this through the API, so the verdict settled the task and is final\./)).toBeInTheDocument();
  });

  it("lists the frozen criteria and fee while waiting, then shows the reviewer at work", async () => {
    apiMock.mockResolvedValue(review({ status: "FROZEN", verdicts: [], rulingCategory: null }));
    const { unmount } = render(<AiReviewReport taskId="t1" taskStatus="EXECUTING" frozen={FROZEN} />);
    expect(screen.getByText("Polite tone")).toBeInTheDocument();
    expect(await screen.findByText(/Waiting for a result that passes the automatic checks\. The 0\.30 cr fee is frozen/)).toBeInTheDocument();
    unmount();

    apiMock.mockResolvedValue(review({ status: "REQUESTED", verdicts: [], rulingCategory: null }));
    render(<AiReviewReport taskId="t1" taskStatus="PENDING_REVIEW" frozen={FROZEN} />);
    expect(await screen.findByRole("status")).toHaveTextContent("The reviewer is reading the result.");
  });

  it("explains a review that failed closed, with the fee refunded", async () => {
    apiMock.mockResolvedValue(review({ status: "FAILED", verdicts: [], rulingCategory: null }));
    render(<AiReviewReport taskId="t1" taskStatus="PENDING_REVIEW" frozen={FROZEN} />);
    expect(await screen.findByText(/couldn't give a usable verdict, so there is no report and the 0\.30 cr fee was refunded\. Judge the result yourself\./)).toBeInTheDocument();
  });

  it("says when the review could not be loaded", async () => {
    apiMock.mockImplementation(() => Promise.reject(new Error("network")));
    render(<AiReviewReport taskId="t1" taskStatus="PENDING_REVIEW" frozen={FROZEN} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Couldn't load the AI Review.");
  });
});
