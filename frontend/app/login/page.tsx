"use client";

import { useState, type FormEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useAuth, homeFor } from "@/lib/auth";
import { errorFor, toFormError, useFocusOnFieldError, validateForm, type FormError } from "@/lib/formErrors";
import { Button, Field, Input } from "@/components/ui";
import { GoogleSignInButton } from "@/components/GoogleSignInButton";
import { AuthAside, AuthStrip } from "@/components/AuthAside";
import { ThemeToggle } from "@/components/ThemeToggle";

const DEMO = [
  { role: "CLIENT", email: "client@hireai.local" },
  { role: "BUILDER", email: "builder@hireai.local" },
  { role: "ADMIN", email: "admin@hireai.local" },
];
const DEMO_PASSWORD = "DemoPass123!";

export default function LoginPage() {
  const { login } = useAuth();
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<FormError | null>(null);
  const clearFieldError = () => setError((er) => (er?.field ? null : er));
  useFocusOnFieldError(error);
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    const invalid = validateForm(e.currentTarget as HTMLFormElement);
    if (invalid) {
      setError(invalid);
      return;
    }
    setBusy(true);
    try {
      const res = await login(email, password);
      router.replace(homeFor(res.roles));
    } catch (err) {
      setError(toFormError(err, { email: "Email", password: "Password" }, "Sign-in failed. Check your connection and try again."));
    } finally {
      setBusy(false);
    }
  }

  function fill(addr: string) {
    setEmail(addr);
    setPassword(DEMO_PASSWORD);
  }

  return (
    <div className="grid min-h-screen lg:grid-cols-[1fr_1.1fr]">
      <a
        href="#auth-form"
        className="sr-only rounded-md bg-accent px-3 py-2 text-sm font-semibold text-ink focus:not-sr-only focus:absolute focus:left-4 focus:top-3 focus:z-50"
      >
        Skip to the form
      </a>
      <AuthAside />

      <div className="relative flex flex-col px-5 py-6 sm:px-10">
        <AuthStrip />
      <div className="flex flex-1 items-center justify-center py-10">
        <div className="w-full max-w-sm">
          <div className="mb-7">
            <h1 className="display text-[2.75rem]">Sign in</h1>
            <p className="mt-2 text-[0.9375rem] leading-relaxed text-muted">
              Clients post tasks and review results. Builders run agents and get paid.
            </p>
          </div>

          <div>
            <form id="auth-form" onSubmit={onSubmit} noValidate className="space-y-4">
              <Field label="Email" htmlFor="email" error={errorFor(error, "email")}>
                <Input
                  id="email"
                  type="email"
                  autoComplete="username"
                  value={email}
                  onChange={(e) => { setEmail(e.target.value); clearFieldError(); }}
                  required
                />
              </Field>
              <Field label="Password" htmlFor="password" error={errorFor(error, "password")}>
                <Input
                  id="password"
                  type="password"
                  autoComplete="current-password"
                  value={password}
                  onChange={(e) => { setPassword(e.target.value); clearFieldError(); }}
                  required
                />
              </Field>
              {error && !error.field && (
                <p
                  role="alert"
                  className="rounded-md border border-red/30 bg-red/10 px-3 py-2 text-xs text-red"
                >
                  {error.message}
                </p>
              )}
              <Button type="submit" disabled={busy} className="w-full">
                {busy ? "Authenticating…" : "Sign in"}
              </Button>
            </form>

            <div className="my-4 flex items-center gap-3">
              <span className="h-px flex-1 bg-line" />
              <span className="text-xs text-dim">or</span>
              <span className="h-px flex-1 bg-line" />
            </div>
            <GoogleSignInButton />
          </div>

          <div className="docket mt-8 p-4" style={{ ["--docket-bg" as string]: "var(--color-canvas)" }}>
            <p className="text-sm font-semibold">Try a demo account</p>
            <p className="mt-0.5 text-xs text-dim">Pick one to fill in the form.</p>
            <div className="mt-3 space-y-2">
              {DEMO.map((d) => (
                <button
                  key={d.email}
                  type="button"
                  onClick={() => fill(d.email)}
                  aria-label={`Fill in the ${d.role.toLowerCase()} demo account, ${d.email}`}
                  className="flex w-full items-center justify-between rounded border border-line bg-surface px-3 py-2 text-left transition hover:border-accent/50"
                >
                  <span className="font-mono text-xs text-fg">{d.email}</span>
                  <span className="text-xs font-semibold text-accent">
                    {d.role === "CLIENT" ? "Client" : d.role === "BUILDER" ? "Builder" : "Admin"}
                  </span>
                </button>
              ))}
            </div>
            <p className="mt-3 text-xs text-dim">
              Password: <span className="font-mono text-muted">{DEMO_PASSWORD}</span>
            </p>
          </div>
          <p className="mt-6 text-center text-sm text-muted">
            No account?{" "}
            <Link href="/register" className="font-semibold text-accent hover:underline">
              Create one
            </Link>
          </p>
        </div>
      </div>
        <ThemeToggle className="absolute right-5 top-6 z-10 border-white/40 bg-surface/90 sm:right-10 lg:bg-transparent" />
      </div>
    </div>
  );
}
