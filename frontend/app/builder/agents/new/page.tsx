"use client";

import { useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import { EMPTY_OUTPUT_SPEC, OutputSpecFields } from "@/lib/outputSpecFields";
import type { AgentDTO, CreateAgentRequest, OutputSpecDTO } from "@/lib/types";
import { Button, Card, Field, Input } from "@/components/ui";

function RegisterAgent() {
  const router = useRouter();
  const [name, setName] = useState("");
  const [categoriesCsv, setCategoriesCsv] = useState("");
  const [webhookUrl, setWebhookUrl] = useState("");
  const [maxExecutionSeconds, setMaxExecutionSeconds] = useState(60);
  const [price, setPrice] = useState(10);
  const [outputSpec, setOutputSpec] = useState<OutputSpecDTO>(EMPTY_OUTPUT_SPEC);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    const body: CreateAgentRequest = {
      name,
      capabilityCategories: categoriesCsv
        .split(",")
        .map((s) => s.trim())
        .filter(Boolean),
      webhookUrl,
      maxExecutionSeconds,
      price,
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
      <header>
        <h1 className="text-2xl font-semibold tracking-tight text-slate-900">Register agent</h1>
        <p className="mt-1 text-sm text-slate-500">
          Declare the binding output contract your agent guarantees.
        </p>
      </header>
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
          <div className="grid grid-cols-2 gap-4">
            <Field label="Max execution seconds" htmlFor="maxExec">
              <Input
                id="maxExec"
                type="number"
                min={1}
                value={maxExecutionSeconds}
                onChange={(e) => setMaxExecutionSeconds(Number(e.target.value))}
                required
              />
            </Field>
            <Field label="Price (credits)" htmlFor="price">
              <Input
                id="price"
                type="number"
                min={0}
                value={price}
                onChange={(e) => setPrice(Number(e.target.value))}
                required
              />
            </Field>
          </div>
          <OutputSpecFields value={outputSpec} onChange={setOutputSpec} />
          {error && (
            <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">
              {error}
            </p>
          )}
          <Button type="submit" disabled={submitting} className="w-full">
            {submitting ? "Registering…" : "Register"}
          </Button>
        </form>
      </Card>
    </div>
  );
}

export default function Page() {
  return (
    <RoleGuard role="BUILDER">
      <RegisterAgent />
    </RoleGuard>
  );
}
