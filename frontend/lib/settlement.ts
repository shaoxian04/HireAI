import type { TaskDTO } from "@/lib/types";

/** The stamp on a settled task's Settlement card: one word, a short note, and its colour. */
export interface SettlementStamp {
  stamp: "PAID" | "REFUNDED" | "SPLIT" | "PART PAID" | "PRO RATA";
  note: string;
  tone: "paid" | "refunded" | "split";
}

/**
 * What the Settlement card stamps for each outcome, so the stamp always agrees with the resolution:
 * accepted (by the Client or automatically) is PAID, a rejection or concession REFUNDED, a split ruling
 * SPLIT, and a multi-Item task stamps how many Items were paid (in full, or pro rata after sampling).
 */
export function settlementStamp(task: TaskDTO): SettlementStamp {
  const items = task.items ?? [];
  if (items.length > 0) {
    const accepted = items.filter((i) => i.status === "ACCEPTED").length;
    const proRata = items.filter((i) => i.status === "PRO_RATA").length;
    const count = task.itemCount ?? items.length;
    if (proRata > 0) return { stamp: "PRO RATA", note: `${accepted} accepted, ${proRata} pro rata`, tone: "split" };
    if (accepted === 0) return { stamp: "REFUNDED", note: "no Item accepted", tone: "refunded" };
    if (accepted < count) return { stamp: "PART PAID", note: `${accepted} of ${count} accepted`, tone: "split" };
    return { stamp: "PAID", note: `${accepted} of ${count} accepted`, tone: "paid" };
  }
  switch (task.resolution) {
    case "ACCEPTED":
      return { stamp: "PAID", note: task.autoAccepted || task.rejection?.decisionSilent ? "auto-accepted" : "work accepted",
        tone: "paid" };
    case "PARTIALLY_ACCEPTED":
      return { stamp: "SPLIT", note: "partly fulfilled", tone: "split" };
    default:
      return { stamp: "REFUNDED", note: task.rejection?.response === "CONCEDE" ? "agent conceded" : "work rejected",
        tone: "refunded" };
  }
}
