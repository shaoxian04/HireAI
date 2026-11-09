"use client";

import { useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { errorFor, toFormError, useFocusOnFieldError, validateForm, type FormError } from "@/lib/formErrors";

const AGENT_FIELDS = {
  name: "Name",
  capabilityCategories: "Capability categories",
  webhookUrl: "Webhook URL",
  price: "Price",
  maxExecutionSeconds: "Max execution seconds",
  maxConcurrent: "Max parallel tasks",
};
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import { setFlash } from "@/lib/flash";
import { CategoryTagsInput } from "@/components/CategoryTagsInput";
import { EMPTY_OUTPUT_SPEC, OutputSpecFields } from "@/lib/outputSpecFields";
import type { AgentDTO, CreateAgentRequest, OutputSpecDTO } from "@/lib/types";
import { Button, Field, Input } from "@/components/ui";
import { PageHeader } from "@/components/PageHeader";
import { MAX_EXECUTION_HINT, MAX_EXECUTION_SECONDS } from "@/lib/agentLimits";

function RegisterAgent() {
  const router = useRouter();
  const [name, setName] = useState("");
  const [categoriesCsv, setCategoriesCsv] = useState("");
  const [webhookUrl, setWebhookUrl] = useState("");
  const [maxExecutionSeconds, setMaxExecutionSeconds] = useState("60");
  const [price, setPrice] = useState("10");
  const [maxConcurrent, setMaxConcurrent] = useState("5");
  const [outputSpec, setOutputSpec] =
    useState<OutputSpecDTO>(EMPTY_OUTPUT_SPEC);
  const [error, setError] = useState<FormError | null>(null);
  // A field error clears the moment that field (or any other) is edited, so it never goes stale.
  const clearFieldError = () => setError((er) => (er?.field ? null : er));
  const [submitting, setSubmitting] = useState(false);
  useFocusOnFieldError(error, { maxExecutionSeconds: "maxExec", capabilityCategories: "categories" });

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    const invalid = validateForm(e.currentTarget as HTMLFormElement, { maxExec: "maxExecutionSeconds", categories: "capabilityCategories" });
    if (invalid) {
      setError(invalid);
      return;
    }
    if (!/^https:\/\//i.test(webhookUrl.trim())) {
      // Dispatch is HTTPS-only; say so here rather than after a round trip.
      setError({ field: "webhookUrl", message: "The webhook URL must use https://" });
      return;
    }
    const maxExecutionSecondsNum =
      maxExecutionSeconds.trim() === "" ? NaN : Number(maxExecutionSeconds);
    if (Number.isNaN(maxExecutionSecondsNum)) {
      setError({ field: "maxExecutionSeconds", message: "Enter how many seconds the agent may take" });
      return;
    }
    const priceNum = price.trim() === "" ? NaN : Number(price);
    if (Number.isNaN(priceNum)) {
      setError({ field: "price", message: "Enter a price in credits" });
      return;
    }
    const maxConcurrentNum =
      maxConcurrent.trim() === "" ? NaN : Number(maxConcurrent);
    if (Number.isNaN(maxConcurrentNum)) {
      setError({ field: "maxConcurrent", message: "Enter how many tasks it can run at once" });
      return;
    }
    setSubmitting(true);
    const body: CreateAgentRequest = {
      name,
      capabilityCategories: categoriesCsv
        .split(",")
        .map((s) => s.trim())
        .filter(Boolean),
      webhookUrl,
      maxExecutionSeconds: maxExecutionSecondsNum,
      price: priceNum,
      maxConcurrent: maxConcurrentNum,
      outputSpec,
    };
    try {
      await api<AgentDTO>("/agents", {
        method: "POST",
        body: JSON.stringify(body),
      });
      setFlash(`${name.trim()} is registered. It starts receiving work once you activate it from the list below.`);
      router.push("/builder");
    } catch (err) {
      setError(toFormError(err, AGENT_FIELDS, "Registration failed. Check your connection and try again."));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="mx-auto max-w-xl space-y-6">
      <PageHeader
        back={{ href: "/builder", label: "My agents" }}
        title="Register an agent"
        description="Declare the output contract your agent guarantees. It binds every direct booking. Webhooks must be HTTPS."
      />

      <div className="docket overflow-hidden">
        <form onSubmit={onSubmit} noValidate>
          <div className="space-y-4 p-6">
            <Field label="Name" htmlFor="name" error={errorFor(error, "name")}>
              <Input
                id="name"
                value={name}
                onChange={(e) => { setName(e.target.value); clearFieldError(); }}
                required
              />
            </Field>
            <Field label="Capability categories" htmlFor="categories" error={errorFor(error, "capabilityCategories")}>
              <CategoryTagsInput id="categories" value={categoriesCsv} onChange={(v) => { setCategoriesCsv(v); clearFieldError(); }} required />
            </Field>
            <Field label="Webhook URL" htmlFor="webhookUrl" error={errorFor(error, "webhookUrl")}>
              <Input
                id="webhookUrl"
                type="url"
                value={webhookUrl}
                onChange={(e) => { setWebhookUrl(e.target.value); clearFieldError(); }}
                placeholder="https://my-agent.example.com/run"
                required
              />
            </Field>
            <div className="grid gap-4 sm:grid-cols-3">
              <Field
                label="Max execution seconds"
                htmlFor="maxExec"
                error={errorFor(error, "maxExecutionSeconds")}
                hint={MAX_EXECUTION_HINT}
              >
                <Input
                  id="maxExec"
                  type="number"
                  min={1}
                  max={MAX_EXECUTION_SECONDS}
                  value={maxExecutionSeconds}
                  onChange={(e) => { setMaxExecutionSeconds(e.target.value); clearFieldError(); }}
                  placeholder="60"
                />
              </Field>
              <Field
                label="Price (credits)"
                htmlFor="price"
                error={errorFor(error, "price")}
                hint={
                  Number(price) > 0
                    ? `You receive ${(Number(price) * 0.85).toFixed(2)} cr per accepted task, after the 15% platform fee.`
                    : "The platform keeps 15% of each accepted task."
                }
              >
                <Input
                  id="price"
                  type="number"
                  min={0}
                  value={price}
                  onChange={(e) => { setPrice(e.target.value); clearFieldError(); }}
                  placeholder="10"
                />
              </Field>
              <Field label="Max parallel tasks" htmlFor="maxConcurrent" error={errorFor(error, "maxConcurrent")}>
                <Input
                  id="maxConcurrent"
                  type="number"
                  min={1}
                  max={100}
                  value={maxConcurrent}
                  onChange={(e) => { setMaxConcurrent(e.target.value); clearFieldError(); }}
                  placeholder="5"
                />
              </Field>
            </div>
          </div>
          <div className="docket-tear" />
          <div className="space-y-4 bg-surface-2 p-6">
            <OutputSpecFields value={outputSpec} onChange={setOutputSpec} />
            {error && !error.field && (
              <p
                role="alert"
                className="rounded-md border border-red/30 bg-red/10 px-3 py-2 text-xs text-red"
              >
                {error.message}
              </p>
            )}
            <Button type="submit" disabled={submitting} className="w-full">
              {submitting ? "Registering…" : "Register agent"}
            </Button>
          </div>
        </form>
      </div>
    </div>
  );
}

export default function Page() {
  return (
    <AppShell>
      <RoleGuard role="BUILDER">
        <RegisterAgent />
      </RoleGuard>
    </AppShell>
  );
}
