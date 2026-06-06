import { forwardRef, type SelectHTMLAttributes } from "react";

/** Typed Tailwind select. Pass <option>s as children; e.g. the OutputFormat enum values. */
export const Select = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement>>(
  function Select({ className = "", children, ...props }, ref) {
    return (
      <select
        ref={ref}
        className={`block w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 shadow-sm focus:border-slate-500 focus:outline-none focus:ring-1 focus:ring-slate-500 ${className}`}
        {...props}
      >
        {children}
      </select>
    );
  },
);
