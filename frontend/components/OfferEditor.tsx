"use client";

import { useId } from "react";
import { Button, Textarea, Input } from "@/components/ui";
import { formatNumber, refOf } from "@/components/AcceptanceTerms";
import type { OfferDTO, OfferRange, OutputSpecDTO, TemplateDTO, TemplateParameterDTO } from "@/lib/types";

/**
 * The Builder's Offer editor (ADR 0009, #89): which Template versions a new agent version accepts and the
 * range of each parameter it can be held to. The draft is plain data (`OfferDraft`) so TabPricing owns it;
 * `offerTermsFromDraft` turns it into the request shape and `offerDraftProblems` catches what the Template's
 * bounds would refuse before the server does (the server checks again and its message is shown as is).
 *
 * The Agent's own output contract (its output spec, which direct bookings freeze) must stay accepted, so its
 * Template can't be dropped and, for a JSON Agent with a schema, its own schema stays in the list.
 */

/** One parameter's choice. A parameter with no entry is "unset": accepted only when a task leaves it unset. */
export type ParamDraft =
  | { mode: "bound"; value: string }
  | { mode: "any" }
  | { mode: "oneOf"; values: string[] }
  | { mode: "flag" };

/** Accepted Template refs (`key@version`) → the parameters given a range. */
export type OfferDraft = Record<string, Record<string, ParamDraft>>;

/** What a direct booking of this Agent freezes: the Template and the values it sets. */
export interface OwnContract {
  ref: string;
  params: Record<string, string>;
}

export function ownContractOf(spec: OutputSpecDTO): OwnContract {
  if (spec.format === "JSON") {
    const schema = spec.schema?.trim() ? spec.schema : null;
    return { ref: "custom_json@1", params: schema ? { schema } : {} };
  }
  return { ref: spec.format === "FILE" ? "file_basic@1" : "text_basic@1", params: {} };
}

function draftOfRange(range: OfferRange): ParamDraft {
  if ("bound" in range) return { mode: "bound", value: String(range.bound) };
  if ("any" in range) return { mode: "any" };
  if ("oneOf" in range) return { mode: "oneOf", values: [...range.oneOf] };
  return { mode: "flag" };
}

export function offerDraftOf(offer: OfferDTO | undefined, own: OwnContract): OfferDraft {
  const draft: OfferDraft = {};
  for (const term of offer?.terms ?? []) {
    const params: Record<string, ParamDraft> = {};
    for (const [name, range] of Object.entries(term.params)) {
      // A flag that does not accept "on" asks nothing, the same as leaving it unset.
      if ("acceptsTrue" in range && !range.acceptsTrue) continue;
      params[name] = draftOfRange(range);
    }
    draft[refOf(term.template, term.version)] = params;
  }
  if (!draft[own.ref]) {
    draft[own.ref] = Object.fromEntries(Object.entries(own.params).map(([n, v]) => [n, { mode: "oneOf", values: [v] }]));
  }
  return draft;
}

function splitRef(ref: string) {
  const at = ref.lastIndexOf("@");
  return { template: ref.slice(0, at), version: Number(ref.slice(at + 1)) };
}

function rangeOf(p: ParamDraft): OfferRange {
  switch (p.mode) {
    case "bound":
      return { bound: Number(p.value) };
    case "any":
      return { any: true };
    case "oneOf":
      return { oneOf: p.values };
    case "flag":
      return { acceptsTrue: true };
  }
}

/** The request's `offer.terms`, in the catalogue's Template order. */
export function offerTermsFromDraft(draft: OfferDraft, templates: TemplateDTO[]) {
  const order = templates.map((t) => refOf(t.key, t.version));
  const refs = [...order.filter((r) => r in draft), ...Object.keys(draft).filter((r) => !order.includes(r))];
  return refs.map((ref) => ({
    ...splitRef(ref),
    params: Object.fromEntries(Object.entries(draft[ref]).map(([name, p]) => [name, rangeOf(p)])),
  }));
}

const fmt = (n: number | undefined) => (n ?? 0).toLocaleString("en-US");

function problemOf(parameter: TemplateParameterDTO, p: ParamDraft): string | null {
  const { bounds } = parameter;
  if (p.mode === "bound") {
    const n = Number(p.value);
    if (p.value.trim() === "" || !Number.isFinite(n) || n < (bounds.minimum ?? -Infinity) || n > (bounds.maximum ?? Infinity)) {
      return `Enter a number between ${fmt(bounds.minimum)} and ${fmt(bounds.maximum)}.`;
    }
  }
  if (p.mode === "oneOf") {
    if (p.values.length === 0) return "Pick at least one, or choose another option.";
    if (p.values.some((v) => v.trim() === "")) return "Fill in or remove the empty value.";
    if (bounds.maxLength && p.values.some((v) => v.length > bounds.maxLength!)) {
      return `Each value must be at most ${fmt(bounds.maxLength)} characters.`;
    }
  }
  return null;
}

/** `ref:param` → what is wrong, for the ranges the Template's bounds would refuse. */
export function offerDraftProblems(draft: OfferDraft, templates: TemplateDTO[]): Record<string, string> {
  const problems: Record<string, string> = {};
  for (const t of templates) {
    const ref = refOf(t.key, t.version);
    const params = draft[ref];
    if (!params) continue;
    for (const parameter of t.parameters) {
      const p = params[parameter.name];
      const problem = p ? problemOf(parameter, p) : null;
      if (problem) problems[`${ref}:${parameter.name}`] = problem;
    }
  }
  return problems;
}

// ── UI ──

function Choice({ name, checked, onChange, label }: { name: string; checked: boolean; onChange: () => void; label: string }) {
  return (
    <label className="inline-flex cursor-pointer items-center gap-2 text-sm text-fg">
      <input type="radio" name={name} checked={checked} onChange={onChange} className="size-4 accent-accent" />
      {label}
    </label>
  );
}

function ParameterEditor({
  parameter,
  value,
  ownValue,
  problem,
  onChange,
}: {
  parameter: TemplateParameterDTO;
  value: ParamDraft | undefined;
  ownValue?: string;
  problem?: string;
  onChange: (p: ParamDraft | undefined) => void;
}) {
  const id = useId();
  const radio = `${id}-mode`;
  const noteId = `${id}-note`;
  const { comparison, bounds, label, name } = parameter;
  const mode = value?.mode ?? "unset";
  const closed = bounds.allowedValues;

  let control: React.ReactNode = null;
  if (comparison === "flag") {
    control = (
      <label className="inline-flex cursor-pointer items-center gap-2 text-sm text-fg">
        <input
          type="checkbox"
          checked={mode === "flag"}
          onChange={(e) => onChange(e.target.checked ? { mode: "flag" } : undefined)}
          className="size-4 accent-accent"
        />
        Accept tasks that turn it on
      </label>
    );
  } else if (comparison === "max" || comparison === "min") {
    const limit = value?.mode === "bound" ? value.value : "";
    const n = Number(limit);
    control = (
      <div className="space-y-2">
        <div className="flex flex-wrap gap-x-5 gap-y-1.5">
          <Choice name={radio} checked={mode === "unset"} onChange={() => onChange(undefined)} label="Only when a task leaves it unset" />
          <Choice
            name={radio}
            checked={mode === "bound"}
            onChange={() => onChange({ mode: "bound", value: String(comparison === "max" ? bounds.maximum ?? "" : bounds.minimum ?? "") })}
            label={comparison === "max" ? "Up to a limit" : "From a limit"}
          />
        </div>
        {mode === "bound" && (
          <div className="flex flex-wrap items-center gap-3">
            <Input
              type="number"
              aria-label={`${label} limit`}
              aria-describedby={noteId}
              aria-invalid={problem ? true : undefined}
              min={bounds.minimum}
              max={bounds.maximum}
              value={limit}
              onChange={(e) => onChange({ mode: "bound", value: e.target.value })}
              className="tabular w-48 font-mono"
            />
            {name.endsWith("_bytes") && limit.trim() !== "" && Number.isFinite(n) && n > 0 && (
              <span className="tabular text-sm text-muted">= {formatNumber(name, n)}</span>
            )}
          </div>
        )}
        {mode === "bound" && !problem && (
          <p id={noteId} className="text-xs text-dim">
            {comparison === "max"
              ? "Tasks asking for up to this are accepted."
              : "Tasks that allow at least this are accepted."}{" "}
            Allowed: {fmt(bounds.minimum)} to {fmt(bounds.maximum)}.
          </p>
        )}
      </div>
    );
  } else {
    const values = value?.mode === "oneOf" ? value.values : [];
    const toOneOf = () => onChange({ mode: "oneOf", values: ownValue !== undefined ? [ownValue] : [] });
    control = (
      <div className="space-y-2">
        <div className="flex flex-wrap gap-x-5 gap-y-1.5">
          {ownValue === undefined && (
            <Choice name={radio} checked={mode === "unset"} onChange={() => onChange(undefined)} label="Only when a task leaves it unset" />
          )}
          <Choice name={radio} checked={mode === "any"} onChange={() => onChange({ mode: "any" })} label="Any value" />
          <Choice name={radio} checked={mode === "oneOf"} onChange={toOneOf} label="Only these" />
        </div>
        {mode === "oneOf" && closed && (
          <div className="flex flex-wrap gap-1.5">
            {closed.map((v) => {
              const on = values.includes(v);
              return (
                <label
                  key={v}
                  className={`inline-flex cursor-pointer items-center gap-1.5 rounded-md border px-2.5 py-1 font-mono text-[0.8125rem] transition has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-accent/30 ${
                    on ? "border-accent/60 bg-accent/10 font-medium text-accent" : "border-line-bright bg-surface text-muted hover:border-accent/40 hover:text-fg"
                  }`}
                >
                  <input
                    type="checkbox"
                    className="sr-only"
                    checked={on}
                    onChange={(e) =>
                      onChange({ mode: "oneOf", values: e.target.checked ? [...values, v] : values.filter((x) => x !== v) })
                    }
                  />
                  {v}
                </label>
              );
            })}
          </div>
        )}
        {mode === "oneOf" && !closed && (
          <div className="space-y-2">
            {values.map((v, i) => {
              const own = ownValue !== undefined && v === ownValue && values.indexOf(v) === i;
              return (
                <div key={i} className="space-y-1">
                  <div className="flex items-start gap-2">
                    <Textarea
                      aria-label={`${label} ${i + 1}`}
                      rows={own ? 2 : 3}
                      readOnly={own}
                      value={v}
                      onChange={(e) =>
                        onChange({ mode: "oneOf", values: values.map((x, j) => (j === i ? e.target.value : x)) })
                      }
                      className={`font-mono text-xs ${own ? "bg-surface-2 text-muted" : ""}`}
                    />
                    {!own && (
                      <Button
                        type="button"
                        variant="ghost"
                        size="sm"
                        aria-label={`Remove ${label} ${i + 1}`}
                        onClick={() => onChange({ mode: "oneOf", values: values.filter((_, j) => j !== i) })}
                      >
                        Remove
                      </Button>
                    )}
                  </div>
                  {own && <p className="text-xs text-dim">Your own {label.toLowerCase()}. It stays, so direct bookings keep working.</p>}
                </div>
              );
            })}
            <Button type="button" variant="secondary" size="sm" onClick={() => onChange({ mode: "oneOf", values: [...values, ""] })}>
              Add another
            </Button>
          </div>
        )}
      </div>
    );
  }

  return (
    <fieldset aria-labelledby={`${id}-label`} className="grid gap-2 border-t border-line pt-3 sm:grid-cols-[11rem_1fr] sm:gap-4">
      <div className="space-y-0.5">
        <p id={`${id}-label`} className="text-sm font-medium text-fg">
          {label}
        </p>
        <p className="font-mono text-[0.75rem] text-dim">
          {name} · {comparison}
        </p>
      </div>
      <div className="min-w-0 space-y-2">
        {parameter.description && <p className="text-xs text-muted">{parameter.description}</p>}
        {control}
        {problem && (
          <p id={noteId} role="alert" className="text-xs font-medium text-red">
            {problem}
          </p>
        )}
      </div>
    </fieldset>
  );
}

function TemplateEditor({
  template,
  params,
  own,
  problems,
  onChange,
}: {
  template: TemplateDTO;
  params: Record<string, ParamDraft> | undefined;
  own: OwnContract;
  problems: Record<string, string>;
  onChange: (params: Record<string, ParamDraft> | undefined) => void;
}) {
  const id = useId();
  const ref = refOf(template.key, template.version);
  const accepted = params !== undefined;
  const isOwn = own.ref === ref;
  return (
    <fieldset
      aria-labelledby={`${id}-title`}
      className={`space-y-3 rounded-lg border p-4 transition ${accepted ? "border-accent/40 bg-accent/[0.03]" : "border-line"}`}
    >
      <div className="flex items-start gap-3">
        <input
          id={`${id}-accept`}
          type="checkbox"
          checked={accepted}
          disabled={isOwn}
          aria-label={`Accept ${template.title}`}
          onChange={(e) => onChange(e.target.checked ? {} : undefined)}
          className="mt-1 size-4 accent-accent disabled:opacity-60"
        />
        <div className="min-w-0 space-y-1">
          <p id={`${id}-title`} className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
            <label htmlFor={`${id}-accept`} className="cursor-pointer font-semibold text-fg">
              {template.title}
            </label>
            <span className="rounded bg-accent/10 px-2 py-0.5 font-mono text-[0.8125rem] font-medium text-accent">{ref}</span>
          </p>
          {template.description && <p className="text-sm text-muted">{template.description}</p>}
          {isOwn && (
            <p className="text-xs text-dim">
              Required: your output contract uses this Template, and direct bookings are judged under it.
            </p>
          )}
        </div>
      </div>
      {accepted && template.parameters.length === 0 && (
        <p className="text-sm text-dim">No parameters to choose: only the Template&apos;s own checks apply.</p>
      )}
      {accepted &&
        template.parameters.map((parameter) => (
          <ParameterEditor
            key={parameter.name}
            parameter={parameter}
            value={params[parameter.name]}
            ownValue={isOwn ? own.params[parameter.name] : undefined}
            problem={problems[`${ref}:${parameter.name}`]}
            onChange={(p) => {
              const next = { ...params };
              if (p) next[parameter.name] = p;
              else delete next[parameter.name];
              onChange(next);
            }}
          />
        ))}
    </fieldset>
  );
}

export function OfferEditor({
  templates,
  value,
  own,
  problems,
  onChange,
}: {
  templates: TemplateDTO[];
  value: OfferDraft;
  own: OwnContract;
  problems: Record<string, string>;
  onChange: (draft: OfferDraft) => void;
}) {
  // A retired or closed version could never be matched; one the Offer already names stays visible.
  const shown = templates.filter(
    (t) => (t.status === "ACTIVE" && t.takesNewContracts !== false) || refOf(t.key, t.version) in value,
  );
  return (
    <div className="space-y-3">
      {shown.map((template) => {
        const ref = refOf(template.key, template.version);
        return (
          <TemplateEditor
            key={ref}
            template={template}
            params={value[ref]}
            own={own}
            problems={problems}
            onChange={(params) => {
              const next = { ...value };
              if (params) next[ref] = params;
              else delete next[ref];
              onChange(next);
            }}
          />
        );
      })}
    </div>
  );
}
