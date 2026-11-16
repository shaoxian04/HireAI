"use client";

import { Field, Input, Select, Skeleton, Textarea } from "@/components/ui";
import { formatNumber, refOf, type TemplatesState } from "@/components/AcceptanceTerms";
import { errorFor, type FormError } from "@/lib/formErrors";
import type { ContractDTO, ContractValue, TemplateDTO, TemplateParameterDTO } from "@/lib/types";

/**
 * The submit form's "Output terms" (#91): which Template the result is delivered under and the
 * parameters the Client wants held to. The shortlist then lists only agents whose Offer covers them,
 * and booking freezes them as the task's Contract. Leaving it on "the agent's own terms" sends no
 * Contract, so the booked agent's own terms apply, as before.
 */
interface Props {
  templates: TemplatesState;
  value: ContractDTO | null;
  onChange: (next: ContractDTO | null) => void;
  /** A form error; one naming a term's input (`term-<name>`) is shown under that term. */
  error?: FormError | null;
}

/** The Contract without `parameter` (a relax suggestion the Client chose to follow). */
export function withoutParameter(contract: ContractDTO, parameter: string): ContractDTO {
  const params = { ...contract.params };
  delete params[parameter];
  return { ...contract, params };
}

export function ContractTermsField({ templates, value, onChange, error = null }: Props) {
  if (templates.status === "loading") {
    return (
      <div role="status" className="space-y-1.5">
        <span className="sr-only">Loading the output terms</span>
        <p className="text-sm font-medium text-fg">Output terms</p>
        <Skeleton className="h-10 w-full" />
      </div>
    );
  }
  if (templates.status === "error") {
    return (
      <div className="space-y-1.5">
        <p className="text-sm font-medium text-fg">Output terms</p>
        <p className="rounded-md border border-amber/30 bg-amber/10 px-3 py-2 text-xs text-fg">
          Couldn&apos;t load the output terms right now. You can still search: the agent you book works
          under its own terms.
        </p>
      </div>
    );
  }
  // Only current versions: during a transition the platform falls back to the previous one itself.
  const active = [...templates.byRef.values()].filter((t) => t.status === "ACTIVE" && t.current !== false);
  const selectedRef = value ? refOf(value.template, value.version) : "";
  const template = value ? templates.byRef.get(selectedRef) : undefined;

  return (
    <div className="space-y-3">
      <Field
        label="Output terms"
        htmlFor="output-terms"
        hint={
          template
            ? "Only agents whose offer accepts these terms are shown, and booking holds the agent to them. Leave a term empty to accept whatever the agent offers."
            : "Optional. Choose how the result is delivered to see only agents that accept your terms. Otherwise the agent you book works under its own."
        }
      >
        <Select
          id="output-terms"
          value={selectedRef}
          onChange={(e) => {
            const next = templates.byRef.get(e.target.value);
            onChange(next ? { template: next.key, version: next.version, params: {} } : null);
          }}
        >
          <option value="">The agent&apos;s own terms</option>
          {active.map((t) => (
            <option key={refOf(t.key, t.version)} value={refOf(t.key, t.version)}>
              {t.title}
            </option>
          ))}
        </Select>
      </Field>

      {value && template && template.parameters.length > 0 && (
        <fieldset className="space-y-4 rounded-lg border border-line bg-surface px-4 py-4">
          <legend className="px-1 font-mono text-xs text-dim">{selectedRef}</legend>
          {template.parameters.map((p) => (
            <ParameterInput
              key={p.name}
              parameter={p}
              value={value.params[p.name]}
              error={errorFor(error, `term-${p.name}`)}
              onChange={(v) => {
                const params = { ...value.params };
                if (v === undefined) delete params[p.name];
                else params[p.name] = v;
                onChange({ ...value, params });
              }}
            />
          ))}
        </fieldset>
      )}
      {value && template && template.parameters.length === 0 && (
        <p className="text-xs text-dim">{template.title} has no terms to set.</p>
      )}
    </div>
  );
}

function ParameterInput({
  parameter: p,
  value,
  error,
  onChange,
}: {
  parameter: TemplateParameterDTO;
  value: ContractValue | undefined;
  error: string | null;
  onChange: (v: ContractValue | undefined) => void;
}) {
  const id = `term-${p.name}`;
  const allowed = p.bounds.allowedValues;

  if (p.comparison === "max" || p.comparison === "min") {
    const n = typeof value === "number" ? value : undefined;
    const range =
      p.bounds.minimum !== undefined && p.bounds.maximum !== undefined
        ? `${formatNumber(p.name, p.bounds.minimum)} to ${formatNumber(p.name, p.bounds.maximum)}`
        : null;
    const hint = [
      p.description,
      n !== undefined && p.name.endsWith("_bytes") ? `That's ${formatNumber(p.name, n)}.` : null,
      range ? `Allowed: ${range}.` : null,
    ]
      .filter(Boolean)
      .join(" ");
    return (
      <Field label={p.label} htmlFor={id} hint={hint || undefined} error={error}>
        <Input
          id={id}
          type="number"
          inputMode="numeric"
          min={p.bounds.minimum}
          max={p.bounds.maximum}
          value={n ?? ""}
          placeholder="Not set"
          onChange={(e) => {
            const raw = e.target.value.trim();
            const parsed = raw === "" ? NaN : Number(raw);
            onChange(Number.isFinite(parsed) ? parsed : undefined);
          }}
        />
      </Field>
    );
  }

  if (p.comparison === "subset" && allowed) {
    const chosen = Array.isArray(value) ? value : [];
    return (
      <fieldset className="space-y-1.5">
        <legend className="text-sm font-medium text-fg">{p.label}</legend>
        {p.description && <p className="text-xs text-dim">{p.description}</p>}
        <div className="flex flex-wrap gap-2 pt-1">
          {allowed.map((v) => {
            const on = chosen.includes(v);
            return (
              <label
                key={v}
                className={`inline-flex cursor-pointer items-center gap-2 rounded-md border px-2.5 py-1.5 font-mono text-xs transition ${on ? "border-accent bg-accent/20 font-medium text-fg" : "border-line text-muted hover:border-line-bright"}`}
              >
                <input
                  type="checkbox"
                  className="accent-[var(--color-accent)]"
                  checked={on}
                  onChange={() => {
                    const next = on ? chosen.filter((c) => c !== v) : [...chosen, v];
                    onChange(next.length ? next : undefined);
                  }}
                />
                {v}
              </label>
            );
          })}
        </div>
        <p className="text-xs text-dim">None ticked: any type the agent accepts.</p>
      </fieldset>
    );
  }

  if (p.comparison === "enum" && allowed) {
    return (
      <Field label={p.label} htmlFor={id} hint={p.description ?? undefined}>
        <Select
          id={id}
          value={typeof value === "string" ? value : ""}
          onChange={(e) => onChange(e.target.value === "" ? undefined : e.target.value)}
        >
          <option value="">Not set</option>
          {allowed.map((v) => (
            <option key={v} value={v}>
              {v}
            </option>
          ))}
        </Select>
      </Field>
    );
  }

  if (p.comparison === "enum") {
    return (
      <Field label={p.label} htmlFor={id} hint={p.description ?? undefined}>
        <Textarea
          id={id}
          rows={3}
          maxLength={p.bounds.maxLength}
          className="font-mono text-xs"
          value={typeof value === "string" ? value : ""}
          placeholder="Not set"
          onChange={(e) => onChange(e.target.value.trim() === "" ? undefined : e.target.value)}
        />
      </Field>
    );
  }

  // flag
  return (
    <label className="flex items-start gap-2.5 text-sm">
      <input
        type="checkbox"
        className="mt-0.5 accent-[var(--color-accent)]"
        checked={value === true}
        onChange={(e) => onChange(e.target.checked ? true : undefined)}
      />
      <span>
        <span className="font-medium text-fg">{p.label}</span>
        {p.description && <span className="block text-xs text-dim">{p.description}</span>}
      </span>
    </label>
  );
}

/** The label a Template gives `parameter`, falling back to its machine name. */
export function parameterLabel(template: TemplateDTO | undefined, parameter: string): string {
  return template?.parameters.find((p) => p.name === parameter)?.label ?? parameter;
}
