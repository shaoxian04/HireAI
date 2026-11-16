import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ReworkNotice } from "./ReworkNotice";

describe("ReworkNotice", () => {
  it("says which rework this is, what failed, and that there is nothing to dispute", () => {
    render(
      <ReworkNotice
        report={{
          verdict: "FAIL",
          checks: [
            { rule: "IMAGE_FORMAT", passed: true, detail: null, outcome: "PASS" },
            { rule: "IMAGE_BACKGROUND_COLOUR", passed: false, detail: "the background is #ff0000", outcome: "REJECT" },
          ],
          rejectedAttempts: 1,
          maxReworks: 2,
        }}
      />,
    );
    expect(screen.getByText("Sent back to the agent for rework")).toBeInTheDocument();
    expect(screen.getByText("Rework 1 of 2")).toBeInTheDocument();
    expect(screen.getByText("The background is the colour the contract asks")).toBeInTheDocument();
    expect(screen.getByText("the background is #ff0000")).toBeInTheDocument();
    expect(screen.getByText(/refunded in full/i)).toBeInTheDocument();
  });

  it("renders nothing without a rejection", () => {
    const { container } = render(<ReworkNotice report={{ verdict: "PASS", checks: [] }} />);
    expect(container).toBeEmptyDOMElement();
    const { container: none } = render(<ReworkNotice report={null} />);
    expect(none).toBeEmptyDOMElement();
  });
});
