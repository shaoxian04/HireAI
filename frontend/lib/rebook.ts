import { api } from "@/lib/api";
import type { MatchPreviewDTO } from "@/lib/types";

/**
 * "Choose another agent" (#79): the new-task flow opened for a failed task. Only the task id travels
 * in the URL; the page reads the task's title, description, category and budget from the API, so
 * none of the Client's text ends up in a query string.
 */
export function rebookHref(taskId: string): string {
  return `/client/tasks/new?rebook=${encodeURIComponent(taskId)}`;
}

/**
 * The match preview for the new-task flow. `exclude` names Agents to leave out of both lists (the
 * Agent that just failed a rebooked task). This is the one place the frontend sends it, so moving
 * the call to a POST body later changes only this function.
 */
export function fetchMatchPreview(
  category: string,
  budget: number,
  exclude: readonly string[] = [],
): Promise<MatchPreviewDTO> {
  const params = new URLSearchParams({ category, budget: String(budget) });
  for (const agentId of exclude) params.append("exclude", agentId);
  return api<MatchPreviewDTO>(`/tasks/match-preview?${params.toString()}`);
}
