import { forwardRef, type SelectHTMLAttributes } from "react";

/** Terminal-style select. Pass <option>s as children; e.g. the OutputFormat enum values. */
export const Select = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement>>(
  function Select({ className = "", children, ...props }, ref) {
    return (
      <select
        ref={ref}
        className={`block w-full rounded-md border border-line bg-surface-2 px-3 py-2 font-mono text-sm text-fg shadow-inner transition focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/25 ${className}`}
        {...props}
      >
        {children}
      </select>
    );
  },
);
