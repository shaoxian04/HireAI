/**
 * The optional Client deadline on the new-task form (#83). The API takes `deadlineSeconds`, counted
 * from the request: the preview leaves out Agents that can't finish in time, and a booking of one is
 * refused.
 */
export type DeadlineUnit = "minutes" | "hours" | "days";

export const DEADLINE_UNITS: { value: DeadlineUnit; label: string; seconds: number }[] = [
  { value: "minutes", label: "minutes", seconds: 60 },
  { value: "hours", label: "hours", seconds: 3_600 },
  { value: "days", label: "days", seconds: 86_400 },
];

/** Mirrors the backend's ClientDeadline.MAX_SECONDS (30 days). */
export const MAX_DEADLINE_SECONDS = 2_592_000;

/**
 * The deadline in seconds: `null` when the field is empty (no deadline), `"invalid"` when it is not a
 * positive whole number or is longer than 30 days.
 */
export function toDeadlineSeconds(amount: string, unit: DeadlineUnit): number | null | "invalid" {
  const trimmed = amount.trim();
  if (trimmed === "") return null;
  const n = Number(trimmed);
  if (!Number.isInteger(n) || n <= 0) return "invalid";
  const perUnit = DEADLINE_UNITS.find((u) => u.value === unit)?.seconds ?? 60;
  const seconds = n * perUnit;
  return seconds > MAX_DEADLINE_SECONDS ? "invalid" : seconds;
}
