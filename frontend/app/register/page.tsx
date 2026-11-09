"use client";

import { useState, type FormEvent } from "react";
import Link from "next/link";
import { AuthAside, AuthStrip } from "@/components/AuthAside";
import { ThemeToggle } from "@/components/ThemeToggle";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { errorFor, toFormError, useFocusOnFieldError, validateForm, type FormError } from "@/lib/formErrors";
import { Button, Field, Input } from "@/components/ui";
import { GoogleSignInButton } from "@/components/GoogleSignInButton";

export default function RegisterPage() {
  const { register } = useAuth();
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [displayName, setDisplayName] = useState("");
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
    if (confirm !== password) {
      // there is no password reset yet, so a typo here would lock the account out
      setError({ field: "confirm", message: "The two passwords don't match" });
      return;
    }
    setBusy(true);
    try {
      await register(email, password, displayName || undefined);
      router.replace("/client");
    } catch (err) {
      setError(toFormError(err, { email: "Email", password: "Password", displayName: "Name" }, "Registration failed. Check your connection and try again."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid min-h-screen lg:grid-cols-[1fr_1.1fr]">
      <a
        href="#auth-form"
        className="sr-only rounded-md bg-accent px-3 py-2 text-sm font-semibold text-ink focus:not-sr-only focus:absolute focus:left-4 focus:top-3 focus:z-50"
      >
        Skip to the form
      </a>
      <AuthAside mode="join" />

      <div className="relative flex flex-col px-5 py-6 sm:px-10">
        <AuthStrip mode="join" />
      <div className="flex flex-1 items-center justify-center py-10">
        <div className="w-full max-w-sm">
          <div className="mb-7">
            <h1 className="display text-[2.75rem]">Create an account</h1>
            <p className="mt-2 text-[0.9375rem] leading-relaxed text-muted">
              You start as a client. You can register as a builder at any time.
            </p>
          </div>

          <div>
            <form id="auth-form" onSubmit={onSubmit} noValidate className="space-y-4">
              <Field label="Name (optional)" htmlFor="displayName">
                <Input id="displayName" autoComplete="name" value={displayName} onChange={(e) => { setDisplayName(e.target.value); clearFieldError(); }} />
              </Field>
              <Field label="Email" htmlFor="email" error={errorFor(error, "email")}>
                <Input id="email" type="email" autoComplete="username" value={email}
                  onChange={(e) => { setEmail(e.target.value); clearFieldError(); }} required />
              </Field>
              <Field label="Password" htmlFor="password" hint="At least 8 characters." error={errorFor(error, "password")}>
                <Input id="password" type="password" autoComplete="new-password" value={password}
                  onChange={(e) => { setPassword(e.target.value); clearFieldError(); }} required minLength={8} />
              </Field>
              <Field label="Confirm password" htmlFor="confirm" error={errorFor(error, "confirm")}>
                <Input id="confirm" type="password" autoComplete="new-password" value={confirm}
                  onChange={(e) => { setConfirm(e.target.value); clearFieldError(); }} required />
              </Field>
              {error && !error.field && (
                <p role="alert" className="rounded-md border border-red/30 bg-red/10 px-3 py-2 text-xs text-red">
                  {error.message}
                </p>
              )}
              <Button type="submit" disabled={busy} className="w-full">
                {busy ? "Creating…" : "Create account"}
              </Button>
            </form>

            <div className="my-4 flex items-center gap-3">
              <span className="h-px flex-1 bg-line" />
              <span className="text-xs text-dim">or</span>
              <span className="h-px flex-1 bg-line" />
            </div>
            <GoogleSignInButton />
          </div>

          <p className="mt-5 text-center text-xs text-muted">
            Already have an account?{" "}
            <Link href="/login" className="text-accent hover:underline">Sign in</Link>
          </p>
        </div>
      </div>
        <ThemeToggle className="absolute right-5 top-6 z-10 border-white/40 bg-surface/90 sm:right-10 lg:bg-transparent" />
      </div>
    </div>
  );
}
