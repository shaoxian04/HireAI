import { describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { ItemNotes } from "@/components/ItemNotes";
import { ResultView } from "@/components/ResultView";
import { SampleItemCard } from "@/components/sampling/SampleItemCard";
import { ItemsPanel } from "@/components/ItemsPanel";
import type { TaskDTO, TaskItemDTO } from "@/lib/types";

const INVOICE = JSON.stringify({
  supplier_name: "Acme Supplies Ltd", supplier_tax_id: "GB123456789", invoice_number: "INV-1005",
  invoice_date: "2026-11-02", currency: "GBP", po_number: null,
  line_items: [{ description: "Widgets", quantity: 2, unit_price: 50, amount: 100 }],
  subtotal: 100, tax_rate: 20, tax_total: 20, grand_total: 130,
});

const FLAGGED: TaskItemDTO = {
  itemNo: 5, input: "inv-1005.pdf", status: "PENDING_REVIEW", price: 2, resultPayload: INVOICE, sourceId: "s-5",
  needsReview: true,
  checks: [
    { rule: "INVOICE_FIELDS", passed: true, detail: "every required field is present", outcome: "PASS" },
    { rule: "INVOICE_TOTAL", passed: false, outcome: "REVIEW",
      detail: "subtotal 100 + tax 20 = 120.00, but the grand total is 130. The Agent reported: the document says 130" },
  ],
  anomalies: [{ check: "INVOICE_TOTAL", description: "the document says 130" }],
  findings: [{ code: "DUPLICATE_INVOICE", detail: "Invoice INV-1005 was already extracted, as Item 1 of this task." }],
};

describe("an invoice Item's notes (#106)", () => {
  it("says why it needs the reviewer, what the agent reported and the Findings that never change the pay", () => {
    render(<ItemNotes item={FLAGGED} />);
    const why = screen.getByLabelText("Why Item 5 needs your review");
    expect(within(why).getByText("Subtotal plus tax is the grand total")).toBeInTheDocument();
    expect(within(why).getByText(/outside the random sample/)).toBeInTheDocument();
    expect(screen.getByText("The agent reported")).toBeInTheDocument();
    expect(screen.getByText(/“the document says 130”/)).toBeInTheDocument();
    const findings = screen.getByLabelText("Findings about Item 5");
    expect(within(findings).getByText("This invoice number from this supplier was extracted before")).toBeInTheDocument();
    expect(within(findings).getByText(/never changes what the agent is paid/)).toBeInTheDocument();
  });

  it("shows nothing for a plain Item", () => {
    const { container } = render(<ItemNotes item={{ itemNo: 1, input: "a", status: "ACCEPTED", price: 1 }} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("lays an extracted invoice out as one, with its lines and totals", () => {
    render(<ResultView payload={INVOICE} />);
    const invoice = screen.getByLabelText("Extracted invoice");
    expect(within(invoice).getByText("INV-1005")).toBeInTheDocument();
    expect(within(invoice).getByText("Widgets")).toBeInTheDocument();
    expect(within(invoice).getByText("Tax (20 %)")).toBeInTheDocument();
    expect(within(invoice).getByText("130.00")).toBeInTheDocument();
    expect(within(invoice).getByText("Total (GBP)")).toBeInTheDocument();
  });

  it("marks a flagged Item as outside the sample on the reviewer page", () => {
    render(<ol><SampleItemCard taskId="t-1" item={FLAGGED} rejection={null} sampled={false} canJudge busy={false}
      now={new Date("2026-11-18T10:00:00Z")} onJudge={vi.fn()} onAccept={vi.fn()} /></ol>);
    expect(screen.getByText(/Flagged by the checks · outside the sample/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Item 5 is correct" })).toBeInTheDocument();
  });

  it("does not list a review check among the failed checks in the Items list", () => {
    const task = { id: "t-1", status: "PENDING_REVIEW", itemCount: 1, itemPrice: 2, items: [FLAGGED],
      reviewDeadline: "2026-11-25T10:00:00Z" } as unknown as TaskDTO;
    render(<ItemsPanel task={task} now={new Date("2026-11-18T10:00:00Z")} onChange={vi.fn()} />);
    expect(screen.getByText("Needs your own check")).toBeInTheDocument();
    expect(screen.queryByLabelText("Checks Item 5 failed")).not.toBeInTheDocument();
  });
});
