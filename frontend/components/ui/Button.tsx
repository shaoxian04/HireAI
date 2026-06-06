import { forwardRef, type ButtonHTMLAttributes } from "react";

type Variant = "primary" | "secondary" | "ghost" | "danger";

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
}

const VARIANTS: Record<Variant, string> = {
  primary:
    "bg-accent text-ink hover:bg-accent-2 focus-visible:ring-accent shadow-[0_0_24px_-8px_var(--color-accent)]",
  secondary:
    "bg-surface-2 text-fg border border-line hover:border-line-bright hover:bg-surface focus-visible:ring-line-bright",
  ghost: "bg-transparent text-muted hover:text-fg hover:bg-surface-2 focus-visible:ring-line-bright",
  danger: "bg-red text-ink hover:brightness-110 focus-visible:ring-red",
};

/** Typed instrument button: mono caps label, variants, focus ring, disabled state. */
export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = "primary", className = "", ...props },
  ref,
) {
  return (
    <button
      ref={ref}
      className={`inline-flex items-center justify-center gap-2 rounded-md px-4 py-2 font-mono text-xs font-semibold uppercase tracking-wider transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:ring-offset-base disabled:cursor-not-allowed disabled:opacity-40 ${VARIANTS[variant]} ${className}`}
      {...props}
    />
  );
});
