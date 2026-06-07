import { forwardRef, type InputHTMLAttributes } from "react";

/** Terminal-style text input. Use inside <Field> for a label + error message. */
export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(
  function Input({ className = "", ...props }, ref) {
    return (
      <input
        ref={ref}
        className={`block w-full rounded-md border border-line bg-surface-2 px-3 py-2 font-mono text-sm text-fg shadow-inner transition placeholder:text-dim focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/25 disabled:opacity-50 ${className}`}
        {...props}
      />
    );
  },
);
