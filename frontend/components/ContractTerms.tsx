import type { OutputSpecDTO } from "@/lib/types";

/** A contract line the builder left blank, drawn as a blank line on the form with what that means. */
function Blank({ children }: { children: React.ReactNode }) {
  return (
    <dd className="border-b border-dashed border-line-bright pb-1 text-[0.8125rem] text-dim">{children}</dd>
  );
}

/** The terms of an output contract as a labelled list: format, schema, acceptance criteria. */
export function ContractTerms({ spec }: { spec: OutputSpecDTO }) {
  return (
    <dl className="divide-y divide-line text-sm">
      <div className="grid gap-1 px-4 py-3 sm:grid-cols-[8rem_1fr] sm:gap-4">
        <dt className="text-dim">Format</dt>
        <dd>
          <span className="rounded bg-accent/10 px-2 py-0.5 font-mono text-[0.8125rem] font-medium text-accent">
            {spec.format}
          </span>
        </dd>
      </div>
      <div className="grid gap-1 px-4 py-3 sm:grid-cols-[8rem_1fr] sm:gap-4">
        <dt className="text-dim">Schema</dt>
        {spec.schema ? (
          <dd className="min-w-0 whitespace-pre-wrap break-words font-mono text-xs text-fg">{spec.schema}</dd>
        ) : (
          <Blank>No schema. Any {spec.format.toLowerCase()} result passes the shape check.</Blank>
        )}
      </div>
      <div className="grid gap-1 px-4 py-3 sm:grid-cols-[8rem_1fr] sm:gap-4">
        <dt className="text-dim">Acceptance</dt>
        {spec.acceptanceCriteria ? (
          <dd className="text-fg">{spec.acceptanceCriteria}</dd>
        ) : (
          <Blank>No acceptance criteria. You judge the result against your brief.</Blank>
        )}
      </div>
    </dl>
  );
}
