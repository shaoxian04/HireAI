import type { ReactNode } from "react";

interface FieldProps {
  label: string;
  htmlFor?: string;
  error?: string | null;
  hint?: string;
  children: ReactNode;
}

/** Form-row wrapper: mono caps label, control (children), optional hint, and an error message. */
export function Field({ label, htmlFor, error, hint, children }: FieldProps) {
  return (
    <div className="space-y-1.5">
      <label
        htmlFor={htmlFor}
        className="block font-mono text-[0.7rem] uppercase tracking-[0.18em] text-muted"
      >
        {label}
      </label>
      {children}
      {hint && !error ? <p className="font-mono text-xs text-dim">{hint}</p> : null}
      {error ? <p className="font-mono text-xs text-red">{error}</p> : null}
    </div>
  );
}
