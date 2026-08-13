import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { TabReviews } from "@/components/manage/TabReviews";
import type { BuilderReviewDTO } from "@/lib/types";

const apiMock = vi.fn();
vi.mock("@/lib/api", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api")>("@/lib/api");
  return { ...actual, api: (...args: unknown[]) => apiMock(...args) };
});

function review(over: Partial<BuilderReviewDTO> = {}): BuilderReviewDTO {
  return {
    id: "r-1",
    rating: 4,
    reviewText: "Clean summary, arrived fast.",
    builderResponse: null,
    createdAt: "2026-08-13T02:45:00Z",
    ...over,
  };
}

describe("TabReviews", () => {
  beforeEach(() => apiMock.mockReset());

  it("leads with the aggregate, not a bare list", async () => {
    apiMock.mockResolvedValue([
      review({ id: "r-1", rating: 5 }),
      review({ id: "r-2", rating: 5 }),
      review({ id: "r-3", rating: 2 }),
    ]);
    render(<TabReviews agentId="a-1" />);

    expect(await screen.findByText("4.0")).toBeInTheDocument();
    expect(screen.getByText(/3 reviews/)).toBeInTheDocument();
  });

  /**
   * The mean hides the shape. A 4.0 from three 4s and a 4.0 from two 5s and a 2 are different
   * problems, and only the distribution tells a builder which one they have.
   */
  it("shows the distribution behind the average", async () => {
    apiMock.mockResolvedValue([
      review({ id: "r-1", rating: 5 }),
      review({ id: "r-2", rating: 5 }),
      review({ id: "r-3", rating: 2 }),
    ]);
    render(<TabReviews agentId="a-1" />);

    await screen.findByText("4.0");
    expect(screen.getByText("5★")).toBeInTheDocument();
    expect(screen.getByText("1★")).toBeInTheDocument();
  });

  /** An always-open editor per review turns a page of feedback into a wall of empty textareas. */
  it("keeps the reply editor collapsed until asked for", async () => {
    apiMock.mockResolvedValue([review()]);
    render(<TabReviews agentId="a-1" />);

    await screen.findByText(/Clean summary/);
    expect(screen.queryByRole("textbox", { name: /response/i })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /^reply$/i }));
    expect(screen.getByRole("textbox", { name: /response/i })).toBeInTheDocument();
  });

  it("publishes a reply and shows it in place", async () => {
    apiMock.mockResolvedValueOnce([review()]);
    render(<TabReviews agentId="a-1" />);

    await screen.findByText(/Clean summary/);
    await userEvent.click(screen.getByRole("button", { name: /^reply$/i }));
    await userEvent.type(screen.getByRole("textbox", { name: /response/i }), "Thanks!");

    apiMock.mockResolvedValueOnce(review({ builderResponse: "Thanks!" }));
    await userEvent.click(screen.getByRole("button", { name: /publish reply/i }));

    expect(await screen.findByText("Thanks!")).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: /response/i })).not.toBeInTheDocument();
  });

  /**
   * ReviewModel.respond() has always set *or replaced* a response, but the old panel hid the editor
   * forever once one existed — stranding a builder with a typo they could not correct.
   */
  it("lets a builder edit a reply they already published", async () => {
    apiMock.mockResolvedValueOnce([review({ builderResponse: "Thnaks!" })]);
    render(<TabReviews agentId="a-1" />);

    await screen.findByText("Thnaks!");
    await userEvent.click(screen.getByRole("button", { name: /edit/i }));

    const box = screen.getByRole("textbox", { name: /response/i });
    // The editor opens pre-filled, so a correction is not a retype.
    expect(box).toHaveValue("Thnaks!");

    await userEvent.clear(box);
    await userEvent.type(box, "Thanks!");
    apiMock.mockResolvedValueOnce(review({ builderResponse: "Thanks!" }));
    await userEvent.click(screen.getByRole("button", { name: /publish reply/i }));

    expect(await screen.findByText("Thanks!")).toBeInTheDocument();
    const [path, init] = apiMock.mock.calls[1];
    expect(path).toBe("/agents/a-1/reviews/r-1/response");
    expect(init.method).toBe("PUT");
    expect(JSON.parse(init.body)).toEqual({ response: "Thanks!" });
  });

  it("cancels an edit without touching the published reply", async () => {
    apiMock.mockResolvedValueOnce([review({ builderResponse: "Original." })]);
    render(<TabReviews agentId="a-1" />);

    await screen.findByText("Original.");
    await userEvent.click(screen.getByRole("button", { name: /edit/i }));
    await userEvent.type(screen.getByRole("textbox", { name: /response/i }), " Edited");
    await userEvent.click(screen.getByRole("button", { name: /cancel/i }));

    expect(screen.getByText("Original.")).toBeInTheDocument();
    expect(apiMock).toHaveBeenCalledTimes(1);
  });

  /** Answering feedback is the builder's actual job on this tab, so it gets a filter. */
  it("can narrow to the reviews still needing a reply", async () => {
    apiMock.mockResolvedValue([
      review({ id: "r-1", reviewText: "Needs an answer." }),
      review({ id: "r-2", reviewText: "Already handled.", builderResponse: "Cheers." }),
    ]);
    render(<TabReviews agentId="a-1" />);

    await screen.findByText("Needs an answer.");
    expect(screen.getByText("Already handled.")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /needs reply 1/i }));

    expect(screen.getByText("Needs an answer.")).toBeInTheDocument();
    expect(screen.queryByText("Already handled.")).not.toBeInTheDocument();
  });

  /**
   * Answering the last outstanding review used to strand the builder: the list filtered to nothing
   * and the control that would have let them back out disappeared with it.
   */
  it("does not strand the builder on an empty list after clearing the queue", async () => {
    apiMock.mockResolvedValueOnce([
      review({ id: "r-1", reviewText: "Needs an answer." }),
      review({ id: "r-2", reviewText: "Already handled.", builderResponse: "Cheers." }),
    ]);
    render(<TabReviews agentId="a-1" />);

    await screen.findByText("Needs an answer.");
    await userEvent.click(screen.getByRole("button", { name: /needs reply 1/i }));
    await userEvent.click(screen.getByRole("button", { name: /^reply$/i }));
    await userEvent.type(screen.getByRole("textbox", { name: /response/i }), "Done.");

    apiMock.mockResolvedValueOnce(
      review({ id: "r-1", reviewText: "Needs an answer.", builderResponse: "Done." }),
    );
    await userEvent.click(screen.getByRole("button", { name: /publish reply/i }));

    // Both reviews are visible again rather than an empty pane behind a vanished filter.
    expect(await screen.findByText("Done.")).toBeInTheDocument();
    expect(screen.getByText("Already handled.")).toBeInTheDocument();
  });

  it("explains that reviews are earned rather than solicited when there are none", async () => {
    apiMock.mockResolvedValue([]);
    render(<TabReviews agentId="a-1" />);

    expect(await screen.findByText("No reviews yet")).toBeInTheDocument();
    expect(screen.getByText(/only a client who accepted and paid/i)).toBeInTheDocument();
  });

  it("surfaces a load failure", async () => {
    apiMock.mockRejectedValueOnce(new Error("boom"));
    render(<TabReviews agentId="a-1" />);

    await waitFor(() => expect(screen.getByRole("alert")).toBeInTheDocument());
  });
});
