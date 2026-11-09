import { forwardRef, type ButtonHTMLAttributes, type ComponentProps } from "react";
import Link from "next/link";

type Variant = "primary" | "secondary" | "ghost" | "danger";
type Size = "sm" | "md";

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
}

const VARIANTS: Record<Variant, string> = {
  primary: "bg-accent text-ink shadow-[inset_0_-2px_0_rgb(0_0_0/0.18)] hover:bg-accent-2 focus-visible:ring-accent",
  secondary:
    "bg-surface text-fg border border-line-bright hover:border-fg/40 hover:bg-surface-2 focus-visible:ring-line-bright",
  ghost: "bg-transparent text-muted hover:text-fg hover:bg-surface-2 focus-visible:ring-line-bright",
  danger:
    "bg-surface text-red border border-red/40 hover:bg-red/5 focus-visible:ring-red",
};

const SIZES: Record<Size, string> = {
  sm: "h-8 px-3 text-[0.8125rem]",
  md: "h-10 px-4 text-sm",
};

const BASE =
  "inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-md font-semibold transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:ring-offset-canvas";

/** Button: sentence-case label, four variants, two sizes, focus ring, disabled state. */
export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = "primary", size = "md", className = "", ...props },
  ref,
) {
  return (
    <button
      ref={ref}
      className={`${BASE} disabled:cursor-not-allowed disabled:border-transparent disabled:bg-line disabled:text-dim disabled:shadow-none ${SIZES[size]} ${VARIANTS[variant]} ${className}`}
      {...props}
    />
  );
});

/**
 * A link that looks like a Button. Use it for navigation CTAs instead of wrapping a <Button> in a
 * <Link>, which is invalid HTML and costs keyboard users a second Tab stop on the same control.
 */
export function ButtonLink({
  variant = "primary",
  size = "md",
  className = "",
  ...props
}: ComponentProps<typeof Link> & { variant?: Variant; size?: Size }) {
  return <Link className={`${BASE} ${SIZES[size]} ${VARIANTS[variant]} ${className}`} {...props} />;
}
