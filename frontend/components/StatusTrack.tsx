import { Fragment } from "react";
import type { TaskStatus } from "@/lib/types";
import { STAGES, taskStage } from "@/lib/taskStage";

/**
 * Compact instrument that plots a task's position along the SUBMIT→SETTLE money path.
 * Filled (lime) = passed, pulsing = current, amber = waiting, red = failed, dim = pending.
 */
export function StatusTrack({ status, labels = false }: { status: TaskStatus; labels?: boolean }) {
  const { index, kind } = taskStage(status);

  function dot(i: number): { cls: string; live: boolean; text: string } {
    if (kind === "done" || i < index) return { cls: "bg-accent", live: false, text: "" };
    if (i === index) {
      if (kind === "fail") return { cls: "bg-red", live: false, text: "text-red" };
      if (kind === "wait") return { cls: "bg-amber", live: true, text: "text-amber" };
      return { cls: "bg-cyan", live: true, text: "text-cyan" };
    }
    return { cls: "bg-line-bright", live: false, text: "" };
  }

  return (
    <div className="flex items-start">
      {STAGES.map((label, i) => {
        const d = dot(i);
        const passed = i < index || kind === "done";
        return (
          <Fragment key={label}>
            <div className={`flex flex-col items-center gap-1.5 ${labels ? "w-14" : ""}`}>
              <span
                className={`relative size-2 rounded-full ${d.cls} ${d.live ? `dot-live ${d.text}` : ""}`}
              />
              {labels && (
                <span
                  className={`font-mono text-[0.58rem] uppercase tracking-[0.14em] ${
                    i <= index || kind === "done" ? "text-muted" : "text-dim"
                  }`}
                >
                  {label}
                </span>
              )}
            </div>
            {i < STAGES.length - 1 && (
              <span
                className={`mt-[3px] h-px flex-1 ${labels ? "min-w-2" : "min-w-4"} ${
                  passed ? "bg-accent/60" : "bg-line"
                }`}
              />
            )}
          </Fragment>
        );
      })}
    </div>
  );
}
