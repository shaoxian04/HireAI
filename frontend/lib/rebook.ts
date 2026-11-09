import { api } from "@/lib/api";
import type { MatchPreviewDTO, MatchPreviewRequest } from "@/lib/types";

/**
 * "Choose another agent" (#79): the new-task flow opened for a failed task. Only the task id travels
 * in the URL; the page reads the task's title, description, category and budget from the API, so
 * none of the Client's text ends up in a query string.
 */
export function rebookHref(taskId: string): string {
  return `/client/tasks/new?rebook=${encodeURIComponent(taskId)}`;
}

/**
 * The match preview for the new-task flow, as a POST so the task text stays out of URLs. An empty
 * `exclude` is left off the body; otherwise it names Agents to leave out of both lists (the Agent
 * that just failed a rebooked task).
 */
export function fetchMatchPreview(request: MatchPreviewRequest): Promise<MatchPreviewDTO> {
  const { exclude, ...rest } = request;
  const body: MatchPreviewRequest = exclude && exclude.length > 0 ? { ...rest, exclude } : rest;
  return api<MatchPreviewDTO>("/tasks/match-preview", {
    method: "POST",
    body: JSON.stringify(body),
  });
}
