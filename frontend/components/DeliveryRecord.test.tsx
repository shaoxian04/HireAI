import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { DeliveryRecord } from "@/components/DeliveryRecord";

describe("DeliveryRecord", () => {
  /**
   * The property the whole two-component split exists to protect, restated at the UI edge: an
   * agent with no evidence must read as UNPROVEN, never as excellent. Rendering nothing here would
   * let the absence of a record pass for a clean one.
   */
  it("reads as unproven when nothing has been witnessed yet", () => {
    render(<DeliveryRecord reliabilitySum={0} reliabilityCount={0} />);

    expect(screen.getByText(/unproven/i)).toBeInTheDocument();
    // Must not assert a completion record it does not have.
    const { container } = render(<DeliveryRecord reliabilitySum={0} reliabilityCount={0} />);
    expect(container.textContent).not.toMatch(/Completed \d/);
  });

  it("states the delivery record in plain language", () => {
    render(<DeliveryRecord reliabilitySum={38} reliabilityCount={40} />);

    expect(screen.getByText(/Completed/)).toBeInTheDocument();
    expect(screen.getByText("38")).toBeInTheDocument();
    expect(screen.getByText("40")).toBeInTheDocument();
  });

  /** Summed quality, not a raw count: a partial ruling contributes 0.5, so 9.5 of 10 rounds to 10. */
  it("rounds summed outcome quality back to a whole task count", () => {
    const { container } = render(<DeliveryRecord reliabilitySum={9.5} reliabilityCount={10} />);

    expect(container.textContent).toMatch(/Completed\s*10\s*of\s*10/);
  });

  it("never shows a raw reputation score", () => {
    const { container } = render(<DeliveryRecord reliabilitySum={38} reliabilityCount={40} />);

    expect(container.textContent).not.toMatch(/rep\b/i);
  });

  it("uses the singular for a single task", () => {
    render(<DeliveryRecord reliabilitySum={1} reliabilityCount={1} />);

    expect(screen.getByText(/task successfully/)).toBeInTheDocument();
  });
});
