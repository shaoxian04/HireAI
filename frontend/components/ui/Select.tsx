import { forwardRef, type SelectHTMLAttributes } from "react";

/** Native select styled to match <Input>. Pass <option>s as children. */
export const Select = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement>>(
  function Select({ className = "", children, ...props }, ref) {
    return (
      <select
        ref={ref}
        className={`block h-10 w-full rounded-md border border-line-bright bg-surface px-3 text-sm text-fg transition focus:border-accent/70 focus:outline-none focus:ring-2 focus:ring-accent/20 ${className}`}
        {...props}
      >
        {children}
      </select>
    );
  },
);
