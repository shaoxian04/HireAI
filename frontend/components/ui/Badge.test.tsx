import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { Badge, statusColor, statusLabel } from "./Badge";

describe("statusColor", () => {
  it("maps every task lifecycle status to a non-empty class string", () => {
    const statuses = [
      "SUBMITTED", "QUEUED", "EXECUTING", "RESULT_RECEIVED", "PENDING_REVIEW",
      "RESOLVED", "AWAITING_AGENT", "TIMED_OUT", "SPEC_VIOLATION", "FAILED", "CANCELLED",
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

describe("statusLabel", () => {
  it("gives known statuses a readable label", () => {
    expect(statusLabel("PENDING_REVIEW")).toBe("Needs review");
    expect(statusLabel("SPEC_VIOLATION")).toBe("Failed validation");
    expect(statusLabel("AWAITING_AGENT")).toBe("Awaiting agent");
  });

  it("sentence-cases an unknown status instead of showing the raw enum", () => {
    expect(statusLabel("SOME_NEW_STATE")).toBe("Some new state");
  });
});

describe("Badge", () => {
  it("renders the readable label and keeps the raw status inspectable", () => {
    render(<Badge status="ACTIVE" />);
    const badge = screen.getByText("Active");
    expect(badge).toHaveAttribute("data-status", "ACTIVE");
  });

  it("prefers explicit children over the label", () => {
    render(<Badge status="RESOLVED">Resolved · accepted</Badge>);
    expect(screen.getByText("Resolved · accepted")).toBeInTheDocument();
  });
});
