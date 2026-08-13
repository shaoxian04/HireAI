import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { RatingPrompt } from "@/components/RatingPrompt";

const apiMock = vi.fn();
vi.mock("@/lib/api", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api")>("@/lib/api");
  return { ...actual, api: (...args: unknown[]) => apiMock(...args) };
});

describe("RatingPrompt", () => {
  beforeEach(() => {
    apiMock.mockReset();
    apiMock.mockResolvedValue({});
  });

  it("posts the chosen stars to the task-scoped review endpoint", async () => {
    render(<RatingPrompt taskId="t-1" />);

    fireEvent.click(screen.getByLabelText("4 stars"));
    fireEvent.click(screen.getByRole("button", { name: /submit rating/i }));

    await waitFor(() => expect(apiMock).toHaveBeenCalledTimes(1));
    const [path, init] = apiMock.mock.calls[0];
    expect(path).toBe("/tasks/t-1/review");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body)).toMatchObject({ rating: 4 });
  });

  /**
   * The prompt must be skippable. A client forced into a snap judgment to get on with their day
   * mostly just skips it — and a rating left later is worth more than one never given.
   */
  it("can be dismissed and reopened without submitting anything", async () => {
    render(<RatingPrompt taskId="t-1" />);

    fireEvent.click(screen.getByRole("button", { name: /not now/i }));
    expect(screen.queryByLabelText("4 stars")).not.toBeInTheDocument();
    expect(apiMock).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: /rate this task/i }));
    expect(screen.getByLabelText("4 stars")).toBeInTheDocument();
  });

  it("does not offer submission until a rating is picked", () => {
    render(<RatingPrompt taskId="t-1" />);

    expect(screen.queryByRole("button", { name: /submit rating/i })).not.toBeInTheDocument();
  });

  it("sends optional prose when given", async () => {
    render(<RatingPrompt taskId="t-1" />);

    fireEvent.click(screen.getByLabelText("1 star"));
    fireEvent.change(screen.getByRole("textbox"), {
      target: { value: "  Conformant but not what I needed.  " },
    });
    fireEvent.click(screen.getByRole("button", { name: /submit rating/i }));

    await waitFor(() => expect(apiMock).toHaveBeenCalled());
    expect(JSON.parse(apiMock.mock.calls[0][1].body)).toMatchObject({
      rating: 1,
      reviewText: "Conformant but not what I needed.",
    });
  });

  it("surfaces a refusal from the server", async () => {
    apiMock.mockRejectedValue(new Error("nope"));
    render(<RatingPrompt taskId="t-1" />);

    fireEvent.click(screen.getByLabelText("5 stars"));
    fireEvent.click(screen.getByRole("button", { name: /submit rating/i }));

    expect(await screen.findByRole("alert")).toBeInTheDocument();
  });
});
