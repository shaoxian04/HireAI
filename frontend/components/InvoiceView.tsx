/** One extracted invoice (ap_invoice_extraction, #106), as the agent delivered it. */
export interface InvoiceResult {
  supplier_name?: unknown;
  supplier_tax_id?: unknown;
  invoice_number?: unknown;
  invoice_date?: unknown;
  currency?: unknown;
  po_number?: unknown;
  line_items: unknown[];
  subtotal?: unknown;
  tax_total?: unknown;
  grand_total?: unknown;
  tax_rate?: unknown;
}

/** True when a parsed result has the shape of an extracted invoice: an invoice number and a list of lines. */
export function isInvoice(value: unknown): value is InvoiceResult {
  if (value === null || typeof value !== "object" || Array.isArray(value)) return false;
  const v = value as Record<string, unknown>;
  return "invoice_number" in v && Array.isArray(v.line_items);
}

function show(v: unknown): string {
  if (v === null || v === undefined || v === "") return "—";
  return typeof v === "object" ? JSON.stringify(v) : String(v);
}

function amount(v: unknown): string {
  return typeof v === "number" ? v.toFixed(2) : show(v);
}

/**
 * An invoice laid out like one, so a reviewer can read it beside its document: who and which invoice, the
 * lines, then the totals. Values are shown exactly as delivered (a wrong type shows as written), since the
 * platform's checks judge those values, not this rendering.
 */
export function InvoiceView({ invoice }: { invoice: InvoiceResult }) {
  const lines = invoice.line_items.map((l) => (l && typeof l === "object" ? (l as Record<string, unknown>) : {}));
  // The tax column only when a line carries its own tax: the result sits in a narrow column beside the document.
  const lineTax = lines.some((l) => l.tax_amount !== undefined && l.tax_amount !== null);
  const currency = typeof invoice.currency === "string" ? invoice.currency : "";
  const head: [string, unknown][] = [
    ["Supplier", invoice.supplier_name],
    ["Tax id", invoice.supplier_tax_id],
    ["Invoice no.", invoice.invoice_number],
    ["Date", invoice.invoice_date],
    ["PO", invoice.po_number],
    ["Currency", invoice.currency],
  ];
  return (
    <div className="space-y-4" aria-label="Extracted invoice">
      <dl className="grid grid-cols-2 gap-x-4 gap-y-2">
        {head.map(([label, v]) => (
          <div key={label} className="min-w-0">
            <dt className="text-xs font-medium text-dim">{label}</dt>
            <dd className="break-words text-sm text-fg">{show(v)}</dd>
          </div>
        ))}
      </dl>
      <div>
        <table className="w-full table-auto text-sm">
          <thead>
            <tr className="border-b border-line text-left text-xs text-dim">
              <th className="py-1.5 pr-3 font-medium">Line</th>
              <th className="py-1.5 pr-2 text-right font-medium">Qty</th>
              <th className="py-1.5 pr-2 text-right font-medium">Unit</th>
              {lineTax && <th className="py-1.5 pr-3 text-right font-medium">Tax</th>}
              <th className="py-1.5 text-right font-medium">Amount</th>
            </tr>
          </thead>
          <tbody className="tabular">
            {lines.map((l, i) => (
              <tr key={i} className="border-b border-line/60">
                <td className="break-words py-1.5 pr-3 text-fg">{show(l.description)}</td>
                <td className="py-1.5 pr-2 text-right font-mono text-xs text-muted">{show(l.quantity)}</td>
                <td className="whitespace-nowrap py-1.5 pr-2 text-right font-mono text-xs text-muted">{amount(l.unit_price)}</td>
                {lineTax && <td className="py-1.5 pr-3 text-right font-mono text-xs text-muted">{amount(l.tax_amount)}</td>}
                <td className="whitespace-nowrap py-1.5 text-right font-mono text-xs text-fg">{amount(l.amount)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <dl className="ml-auto w-full max-w-[16rem] space-y-1 text-sm tabular">
        <div className="flex justify-between gap-4"><dt className="text-dim">Subtotal</dt><dd className="font-mono text-fg">{amount(invoice.subtotal)}</dd></div>
        <div className="flex justify-between gap-4">
          <dt className="text-dim">{invoice.tax_rate !== undefined && invoice.tax_rate !== null ? `Tax (${show(invoice.tax_rate)} %)` : "Tax"}</dt>
          <dd className="font-mono text-fg">{amount(invoice.tax_total)}</dd>
        </div>
        <div className="flex justify-between gap-4 border-t border-line pt-1 font-semibold">
          <dt className="text-fg">Total{currency ? ` (${currency})` : ""}</dt>
          <dd className="font-mono text-fg">{amount(invoice.grand_total)}</dd>
        </div>
      </dl>
    </div>
  );
}
