"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { Skeleton } from "@/components/ui";
import type {
  ContractDTO,
  ContractValue,
  OfferDTO,
  OfferRange,
  OutputFormat,
  TemplateDTO,
  TemplateParameterDTO,
} from "@/lib/types";

/**
 * The acceptance terms of ADR 0009 in plain words: a Task's frozen Contract (`ContractPanel`) and an
 * agent version's Offer (`OfferPanel`). Template titles and parameter labels come from
 * `GET /catalogue/templates`; if that fails the terms still show, by their machine names.
 */

export type TemplatesState = { status: "loading" } | { status: "ready"; byRef: Map<string, TemplateDTO> } | { status: "error" };

export function refOf(key: string, version: number) {
  return `${key}@${version}`;
}

/** The published Templates by `key@version`, fetched once `enabled`. */
export function useTemplates(enabled: boolean): TemplatesState {
  const [state, setState] = useState<TemplatesState>({ status: "loading" });
  useEffect(() => {
    if (!enabled) return;
    let live = true;
    api<TemplateDTO[]>("/catalogue/templates")
      .then((list) => {
        if (!live) return;
        const byRef = new Map<string, TemplateDTO>();
        for (const t of Array.isArray(list) ? list : []) {
          if (t && typeof t.key === "string") byRef.set(refOf(t.key, t.version), t);
        }
        setState({ status: "ready", byRef });
      })
      .catch(() => live && setState({ status: "error" }));
    return () => {
      live = false;
    };
  }, [enabled]);
  return state;
}

function formatBytes(n: number) {
  const units = ["bytes", "KB", "MB", "GB"];
  let value = n;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${Number.isInteger(value) ? value : value.toFixed(1)} ${units[unit]}`;
}

export function formatNumber(name: string, n: number) {
  return name.endsWith("_bytes") ? formatBytes(n) : n.toLocaleString("en-US");
}

/** Long or JSON-looking text is machine data: show it in mono, wrapped. */
function TextValue({ value }: { value: string }) {
  const machine = value.length > 48 || /^[[{]/.test(value.trim());
  return machine ? (
    <code className="block min-w-0 whitespace-pre-wrap break-words font-mono text-xs text-fg">{value}</code>
  ) : (
    <span className="text-fg">{value}</span>
  );
}

export function ContractValueView({ name, value }: { name: string; value: ContractValue }) {
  if (typeof value === "number") return <span className="tabular text-fg">{formatNumber(name, value)}</span>;
  if (typeof value === "boolean") return <span className="text-fg">{value ? "Yes" : "No"}</span>;
  if (Array.isArray(value)) return <span className="text-fg">{value.length ? value.join(", ") : "None"}</span>;
  return <TextValue value={value} />;
}

function RangeView({ name, range, parameter }: { name: string; range: OfferRange; parameter?: TemplateParameterDTO }) {
  if ("bound" in range) {
    const v = formatNumber(name, range.bound);
    return <span className="tabular text-fg">{parameter?.comparison === "max" ? `Up to ${v}` : `${v} or more`}</span>;
  }
  if ("any" in range) return <span className="text-fg">Any value</span>;
  if ("acceptsTrue" in range) return <span className="text-fg">{range.acceptsTrue ? "Accepted" : "Not accepted"}</span>;
  const values = range.oneOf;
  if (values.length === 0) return <span className="text-fg">None</span>;
  if (values.length === 1) {
    return (
      <div className="space-y-1">
        <span className="text-sm text-muted">Only this value:</span>
        <TextValue value={values[0]} />
      </div>
    );
  }
  return <span className="text-fg">{`${parameter?.comparison === "subset" ? "Any of" : "One of"}: ${values.join(", ")}`}</span>;
}

function TermsRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="grid gap-1 py-2.5 sm:grid-cols-[10rem_1fr] sm:gap-4">
      <dt className="text-sm text-dim">{label}</dt>
      <dd className="min-w-0 text-sm">{children}</dd>
    </div>
  );
}

function TemplateHeading({ template, refText }: { template?: TemplateDTO; refText: string }) {
  return (
    <div className="space-y-1">
      <p className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        {template && <span className="font-semibold text-fg">{template.title}</span>}
        <span className="rounded bg-accent/10 px-2 py-0.5 font-mono text-[0.8125rem] font-medium text-accent">
          {refText}
        </span>
      </p>
      {template?.description && <p className="text-sm text-muted">{template.description}</p>}
    </div>
  );
}

function LoadingTerms({ label }: { label: string }) {
  return (
    <div role="status" className="space-y-2 pt-1">
      <span className="sr-only">{label}</span>
      <Skeleton className="h-4 w-2/5" />
      <Skeleton className="h-4 w-3/5" />
    </div>
  );
}

function TemplatesUnavailable() {
  return (
    <p className="text-xs text-amber">
      Couldn&apos;t load what this Template means right now. The terms below still apply.
    </p>
  );
}

/**
 * A Task's Contract: the Template version it is judged under and the parameters frozen at submit. Every
 * task has one (the output spec is retired, #110).
 */
export function ContractPanel({ contract }: { contract: ContractDTO | null | undefined }) {
  const templates = useTemplates(Boolean(contract));

  if (!contract) return null;
  if (templates.status === "loading") return <LoadingTerms label="Loading the contract terms" />;

  const refText = refOf(contract.template, contract.version);
  const template = templates.status === "ready" ? templates.byRef.get(refText) : undefined;
  const params = Object.entries(contract.params);
  return (
    <div className="space-y-3">
      {templates.status === "error" && <TemplatesUnavailable />}
      <TemplateHeading template={template} refText={refText} />
      {params.length === 0 ? (
        <p className="text-sm text-dim">No parameters are set, so only the Template&apos;s own checks apply.</p>
      ) : (
        <dl className="divide-y divide-line">
          {params.map(([name, value]) => (
            <TermsRow key={name} label={template?.parameters.find((p) => p.name === name)?.label ?? name}>
              <ContractValueView name={name} value={value} />
            </TermsRow>
          ))}
        </dl>
      )}
    </div>
  );
}

/** An agent version's Offer: its price and, per Template version, the ranges it accepts. */
export function OfferPanel({ offer }: { offer: OfferDTO | undefined }) {
  const templates = useTemplates(Boolean(offer));

  if (!offer) {
    return <p className="text-sm text-muted">No Offer to show for this version yet.</p>;
  }
  if (templates.status === "loading") return <LoadingTerms label="Loading the offer terms" />;

  return (
    <div className="space-y-4">
      {templates.status === "error" && <TemplatesUnavailable />}
      <p className="text-sm text-muted">
        Price <span className="tabular font-bold text-fg">{offer.price} credits</span> per task.
      </p>
      <ul className="space-y-4">
        {offer.terms.map((term) => {
          const refText = refOf(term.template, term.version);
          const template = templates.status === "ready" ? templates.byRef.get(refText) : undefined;
          const named = Object.entries(term.params);
          const unnamed = (template?.parameters ?? []).filter((p) => !(p.name in term.params));
          return (
            <li key={refText} className="space-y-2">
              <TemplateHeading template={template} refText={refText} />
              {named.length > 0 && (
                <dl className="divide-y divide-line">
                  {named.map(([name, range]) => {
                    const parameter = template?.parameters.find((p) => p.name === name);
                    return (
                      <TermsRow key={name} label={parameter?.label ?? name}>
                        <RangeView name={name} range={range} parameter={parameter} />
                      </TermsRow>
                    );
                  })}
                </dl>
              )}
              {unnamed.length > 0 && (
                <p className="text-sm text-dim">
                  {unnamed.map((p) => p.label).join(", ")}: accepted only when a task&apos;s contract leaves{" "}
                  {unnamed.length === 1 ? "it" : "them"} unset.
                </p>
              )}
              {named.length === 0 && unnamed.length === 0 && template && (
                <p className="text-sm text-dim">This Template has no parameters to choose.</p>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
