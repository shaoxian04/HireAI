import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { Badge, statusColor } from "./Badge";

describe("statusColor", () => {
  it("maps every task lifecycle status to a non-empty class string", () => {
    const statuses = [
      "SUBMITTED", "QUEUED", "EXECUTING", "RESULT_RECEIVED", "PENDING_REVIEW",
      "RESOLVED", "AWAITING_CAPACITY", "TIMED_OUT", "SPEC_VIOLATION", "FAILED", "CANCELLED",
    ];
    for (const s of statuses) expect(statusColor(s).length).toBeGreaterThan(0);
  });

  it("maps agent statuses too", () => {
    for (const s of ["PENDING_VERIFICATION", "ACTIVE", "SUSPENDED", "DEACTIVATED"]) {
      expect(statusColor(s).length).toBeGreaterThan(0);
    }
  });

  it("falls back to a neutral class for unknown statuses", () => {
    expect(statusColor("WHATEVER").length).toBeGreaterThan(0);
  });
});

describe("Badge", () => {
  it("renders its status text", () => {
    render(<Badge status="ACTIVE" />);
    expect(screen.getByText("ACTIVE")).toBeInTheDocument();
  });
});
