import { cloneElement, isValidElement, type ReactElement, type ReactNode } from "react";

interface FieldProps {
  label: string;
  htmlFor?: string;
  error?: string | null;
  hint?: string;
  children: ReactNode;
}

/**
 * Form row: label, control (children), optional hint, and an error message. When the control is a
 * single element with an id, the hint/error is wired to it (aria-describedby, aria-invalid) so a
 * screen reader announces why the field was rejected.
 */
export function Field({ label, htmlFor, error, hint, children }: FieldProps) {
  const noteId = htmlFor ? `${htmlFor}-note` : undefined;
  const note = error ?? hint ?? null;
  const control =
    noteId && note && isValidElement(children)
      ? cloneElement(children as ReactElement<Record<string, unknown>>, {
          "aria-describedby": noteId,
          ...(error ? { "aria-invalid": true } : {}),
        })
      : children;

  return (
    <div className="space-y-1.5">
      <label htmlFor={htmlFor} className="block text-sm font-medium text-fg">
        {label}
      </label>
      {control}
      {hint && !error ? (
        <p id={noteId} className="text-xs text-dim">
          {hint}
        </p>
      ) : null}
      {error ? (
        <p id={noteId} role="alert" className="flex items-start gap-1.5 text-xs font-medium text-red">
          <svg viewBox="0 0 12 12" className="mt-px size-3 shrink-0" aria-hidden>
            <circle cx="6" cy="6" r="5.25" fill="none" stroke="currentColor" strokeWidth="1.5" />
            <path d="M6 3.2v3.3M6 8.4v.1" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
          </svg>
          {error}
        </p>
      ) : null}
    </div>
  );
}
