"use client";

import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import { Badge, Button } from "@/components/ui";
import type { WebhookDeliveryDTO } from "@/lib/types";

interface Props {
  taskId: string;
}

const LABELS: Record<WebhookDeliveryDTO["status"], string> = {
  PENDING: "Pending",
  DELIVERED: "Delivered",
  DEAD: "Failed",
};

/**
 * Compact webhook-push indicator for a single task's detail view — lets a client who submitted
 * this task via an API key see, at a glance, whether the platform's task.completed/task.failed
 * push actually reached their endpoint, without cross-referencing the full /client/webhooks log.
 *
 * Renders nothing for a WEB task or an API task with no subscription registered — there is
 * simply no delivery row to show, and a client who never used the API channel should never see
 * a webhook panel at all.
 */
export default function WebhookDeliveryStatus({ taskId }: Props) {
  const [deliveries, setDeliveries] = useState<WebhookDeliveryDTO[] | null>(null);
  const [resending, setResending] = useState(false);

  const load = useCallback(() => {
    api<WebhookDeliveryDTO[]>(`/webhooks/deliveries?taskId=${taskId}`)
      .then(setDeliveries)
      // Best-effort display only — a fetch failure just hides the indicator rather than
      // surfacing a page-level error for what is a secondary, informational panel.
      .catch(() => setDeliveries([]));
  }, [taskId]);

  useEffect(() => {
    load();
  }, [load]);

  if (!deliveries || deliveries.length === 0) return null;

  // A given task normally has at most one webhook event in flight per lifecycle transition, but
  // sort defensively so the newest row wins regardless of what order the backend returns them in.
  const latest = [...deliveries].sort(
    (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
  )[0];

  async function resend() {
    setResending(true);
    try {
      await api<void>(`/webhooks/deliveries/${latest.eventId}/redeliver`, { method: "POST" });
      load();
    } catch {
      // Best-effort — the badge simply stays DEAD if the resend call itself fails; the client
      // can retry from here or from the full /client/webhooks console.
    } finally {
      setResending(false);
    }
  }

  return (
    <div className="flex items-center gap-2 font-mono text-xs">
      <span className="uppercase tracking-wider text-dim">Webhook</span>
      <Badge status={latest.status}>{LABELS[latest.status]}</Badge>
      {latest.status === "DEAD" && (
        <Button variant="ghost" onClick={resend} disabled={resending}>
          {resending ? "…" : "Resend"}
        </Button>
      )}
    </div>
  );
}
