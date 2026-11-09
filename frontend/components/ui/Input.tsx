import { forwardRef, type InputHTMLAttributes, type TextareaHTMLAttributes } from "react";

/** Text input. Use inside <Field> for a label + error message. Add `font-mono` for data entry. */
export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(
  function Input({ className = "", ...props }, ref) {
    return (
      <input
        ref={ref}
        className={`block h-10 w-full rounded-md border border-line-bright bg-surface px-3 text-sm text-fg transition placeholder:text-dim focus:border-accent/70 focus:outline-none focus:ring-2 focus:ring-accent/20 disabled:opacity-50 ${className}`}
        {...props}
      />
    );
  },
);

/** Multi-line text input, styled to match Input. */
export const Textarea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement>>(
  function Textarea({ className = "", rows = 4, ...props }, ref) {
    return (
      <textarea
        ref={ref}
        rows={rows}
        className={`block w-full rounded-md border border-line-bright bg-surface px-3 py-2 text-sm leading-relaxed text-fg transition placeholder:text-dim focus:border-accent/70 focus:outline-none focus:ring-2 focus:ring-accent/20 disabled:opacity-50 ${className}`}
        {...props}
      />
    );
  },
);
