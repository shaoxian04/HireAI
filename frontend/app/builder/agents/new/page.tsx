"use client";

import { useState, type FormEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import { EMPTY_OUTPUT_SPEC, OutputSpecFields } from "@/lib/outputSpecFields";
import type { AgentDTO, CreateAgentRequest, OutputSpecDTO } from "@/lib/types";
import { Button, Card, Field, Input } from "@/components/ui";

function RegisterAgent() {
  const router = useRouter();
  const [name, setName] = useState("");
  const [categoriesCsv, setCategoriesCsv] = useState("");
  const [webhookUrl, setWebhookUrl] = useState("");
  const [maxExecutionSeconds, setMaxExecutionSeconds] = useState("60");
  const [price, setPrice] = useState("10");
  const [maxConcurrent, setMaxConcurrent] = useState("5");
  const [outputSpec, setOutputSpec] = useState<OutputSpecDTO>(EMPTY_OUTPUT_SPEC);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    const maxExecutionSecondsNum =
      maxExecutionSeconds.trim() === "" ? NaN : Number(maxExecutionSeconds);
    if (Number.isNaN(maxExecutionSecondsNum)) {
      setError("Enter max execution seconds");
      return;
    }
    const priceNum = price.trim() === "" ? NaN : Number(price);
    if (Number.isNaN(priceNum)) {
      setError("Enter a price");
      return;
    }
    const maxConcurrentNum = maxConcurrent.trim() === "" ? NaN : Number(maxConcurrent);
    if (Number.isNaN(maxConcurrentNum)) {
      setError("Enter max parallel tasks");
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
      await api<AgentDTO>("/agents", { method: "POST", body: JSON.stringify(body) });
      router.push("/builder");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Registration failed");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="mx-auto max-w-xl space-y-6">
      <div>
        <Link href="/builder" className="font-mono text-xs text-dim transition hover:text-accent">
          ← console
        </Link>
        <p className="eyebrow mt-4 flex items-center gap-2">
          <span className="inline-block h-px w-6 bg-accent" />
          New agent
        </p>
        <h1 className="mt-3 text-3xl font-extrabold tracking-tight">Register agent</h1>
        <p className="mt-2 text-sm text-muted">
          Declare the binding output contract your agent guarantees. Webhooks must be HTTPS.
        </p>
      </div>

      <Card>
        <form onSubmit={onSubmit} className="space-y-4">
          <Field label="Name" htmlFor="name">
            <Input id="name" value={name} onChange={(e) => setName(e.target.value)} required />
          </Field>
          <Field label="Capability categories (comma-separated)" htmlFor="categories">
            <Input
              id="categories"
              value={categoriesCsv}
              onChange={(e) => setCategoriesCsv(e.target.value)}
              placeholder="summarisation, translation"
              required
            />
          </Field>
          <Field label="Webhook URL" htmlFor="webhookUrl">
            <Input
              id="webhookUrl"
              type="url"
              value={webhookUrl}
              onChange={(e) => setWebhookUrl(e.target.value)}
              placeholder="https://my-agent.example.com/run"
              required
            />
          </Field>
          <div className="grid grid-cols-3 gap-4">
            <Field label="Max execution seconds" htmlFor="maxExec">
              <Input
                id="maxExec"
                type="number"
                min={1}
                value={maxExecutionSeconds}
                onChange={(e) => setMaxExecutionSeconds(e.target.value)}
                placeholder="60"
              />
            </Field>
            <Field label="Price (credits)" htmlFor="price">
              <Input
                id="price"
                type="number"
                min={0}
                value={price}
                onChange={(e) => setPrice(e.target.value)}
                placeholder="10"
              />
            </Field>
            <Field label="Max parallel tasks" htmlFor="maxConcurrent">
              <Input
                id="maxConcurrent"
                type="number"
                min={1}
                max={100}
                value={maxConcurrent}
                onChange={(e) => setMaxConcurrent(e.target.value)}
                placeholder="5"
              />
            </Field>
          </div>
          <OutputSpecFields value={outputSpec} onChange={setOutputSpec} />
          {error && (
            <p
              role="alert"
              className="rounded-md border border-red/30 bg-red/10 px-3 py-2 font-mono text-xs text-red"
            >
              {error}
            </p>
          )}
          <Button type="submit" disabled={submitting} className="w-full">
            {submitting ? "Registering…" : "Register agent ▸"}
          </Button>
        </form>
      </Card>
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
