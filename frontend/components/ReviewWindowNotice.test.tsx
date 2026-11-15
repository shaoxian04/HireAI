import { describe, it, expect, vi, afterEach } from "vitest";
import { act, render, screen } from "@testing-library/react";
import { ReviewWindowNotice } from "@/components/ReviewWindowNotice";

const NOW = new Date("2026-11-15T10:00:00Z");

describe("ReviewWindowNotice", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("renders nothing without a review deadline", () => {
    const { container } = render(<ReviewWindowNotice deadline={null} now={NOW} />);
    expect(container.firstChild).toBeNull();
  });

  it("shows the time left and what happens when it runs out", () => {
    render(<ReviewWindowNotice deadline="2026-11-16T08:30:00Z" now={NOW} />);
    expect(screen.getByText("22h 30m left")).toBeTruthy();
    expect(screen.getByText(/accepted automatically and the builder is paid/i)).toBeTruthy();
  });

  it("counts minutes once less than an hour is left", () => {
    render(<ReviewWindowNotice deadline="2026-11-15T10:12:00Z" now={NOW} />);
    expect(screen.getByText("12m left")).toBeTruthy();
  });

  it("says the result is being accepted once the window has closed", () => {
    render(<ReviewWindowNotice deadline="2026-11-15T09:59:00Z" now={NOW} />);
    expect(screen.getByText(/review window has closed/i)).toBeTruthy();
    expect(screen.queryByText(/left$/)).toBeNull();
  });

  it("counts down on its own", () => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
    render(<ReviewWindowNotice deadline="2026-11-15T10:02:00Z" />);
    expect(screen.getByText("2m left")).toBeTruthy();
    act(() => {
      vi.advanceTimersByTime(60_000);
    });
    expect(screen.getByText("1m left")).toBeTruthy();
  });
});
