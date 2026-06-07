"use client";

import { Field, Input, Select } from "@/components/ui";
import type { OutputFormat, OutputSpecDTO } from "@/lib/types";

const OUTPUT_FORMATS: OutputFormat[] = ["TEXT", "JSON", "FILE"];

/** Default contract used to seed the agent-register and task-submit forms. */
export const EMPTY_OUTPUT_SPEC: OutputSpecDTO = {
  format: "JSON",
  schema: "",
  acceptanceCriteria: "",
};

/**
 * Controlled sub-form for the binding output contract, reused by the agent-register and
 * task-submit screens. The parent owns the `OutputSpecDTO` state and passes `value` + `onChange`,
 * keeping both forms DRY and the format options pinned to the exact `OutputFormat` enum values.
 */
export function OutputSpecFields({
  value,
  onChange,
}: {
  value: OutputSpecDTO;
  onChange: (next: OutputSpecDTO) => void;
}) {
  const set = <K extends keyof OutputSpecDTO>(key: K, v: OutputSpecDTO[K]) =>
    onChange({ ...value, [key]: v });

  return (
    <fieldset className="space-y-4 rounded-lg border border-line bg-surface-2/50 p-4">
      <legend className="px-2 font-mono text-[0.65rem] font-semibold uppercase tracking-[0.18em] text-accent">
        Output contract
      </legend>
      <Field label="Format" htmlFor="outputSpec.format">
        <Select
          id="outputSpec.format"
          value={value.format}
          onChange={(e) => set("format", e.target.value as OutputFormat)}
        >
          {OUTPUT_FORMATS.map((f) => (
            <option key={f} value={f}>
              {f}
            </option>
          ))}
        </Select>
      </Field>
      <Field label="Schema" htmlFor="outputSpec.schema">
        <Input
          id="outputSpec.schema"
          value={value.schema}
          onChange={(e) => set("schema", e.target.value)}
          placeholder='e.g. {"type":"object"}'
        />
      </Field>
      <Field label="Acceptance criteria" htmlFor="outputSpec.acceptanceCriteria">
        <Input
          id="outputSpec.acceptanceCriteria"
          value={value.acceptanceCriteria}
          onChange={(e) => set("acceptanceCriteria", e.target.value)}
          placeholder="Plain-language criteria the deliverable must meet"
        />
      </Field>
    </fieldset>
  );
}
