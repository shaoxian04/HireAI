import { useEffect } from "react";
import { ApiError } from "./api";

export type FormError = { field: string | null; message: string };

function sentence(s: string): string {
  const t = s.trim();
  return t ? t.charAt(0).toUpperCase() + t.slice(1) : t;
}

/**
 * Splits a failed submit into "which field" + "what to say". The backend reports bean-validation
 * failures as `"<field>: <reason>"` (first failing field only); when that field is one this form
 * shows, the reason goes under it. Anything else is a form-level message.
 *
 * `fields` maps the backend field name to the label the form uses, e.g. `{ title: "Title" }`.
 */
export function toFormError(err: unknown, fields: Record<string, string>, fallback: string): FormError {
  if (!(err instanceof ApiError)) return { field: null, message: fallback };
  const m = /^([\w.]+):\s*(.+)$/.exec(err.message);
  if (m) {
    const [, key, reason] = m;
    if (key in fields) return { field: key, message: `${fields[key]} ${reason.trim()}` };
    return { field: null, message: `${sentence(key)} ${reason}` };
  }
  return { field: null, message: err.message || fallback };
}

/** The error to show under one field, or null. */
export function errorFor(e: FormError | null, field: string): string | null {
  return e && e.field === field ? e.message : null;
}

/**
 * Moves focus to the field an error names, so a rejected field that is scrolled out of view (or
 * above the submit button) is where the user lands. `ids` maps a field name to its input id when
 * they differ.
 */
export function useFocusOnFieldError(e: FormError | null, ids: Record<string, string> = {}): void {
  const field = e?.field ?? null;
  useEffect(() => {
    if (!field) return;
    const el = document.getElementById(ids[field] ?? field);
    el?.focus();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- ids is a static lookup per form
  }, [e]);
}

type Control = HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement;

function labelFor(form: HTMLFormElement, el: Control): string {
  const text = form.querySelector(`label[for="${CSS.escape(el.id)}"]`)?.textContent ?? el.name ?? "This field";
  return text.replace(/\s*\(.*\)\s*$/, "").trim(); // "Price (credits)" → "Price"
}

/**
 * Checks a form (rendered with `noValidate`) the way the browser would, but returns the first
 * problem as a FormError so it renders under the field in the app's own style rather than as a
 * native bubble. A value of only spaces counts as empty. `idToField` maps an input id to the
 * field name the form's errors use, when they differ.
 */
export function validateForm(form: HTMLFormElement, idToField: Record<string, string> = {}): FormError | null {
  const controls = Array.from(form.querySelectorAll<Control>("input, textarea, select"));
  for (const el of controls) {
    if (!el.id || el.disabled || el.type === "hidden") continue;
    const label = labelFor(form, el);
    const field = idToField[el.id] ?? el.id;
    if (el.required && el.value.trim() === "") return { field, message: `${label} is required` };
    if (el instanceof HTMLInputElement && el.minLength > 0 && el.value.length < el.minLength)
      return { field, message: `${label} needs at least ${el.minLength} characters` };
    if (el.checkValidity()) continue;
    const v = el.validity;
    if (v.typeMismatch && el.type === "url") return { field, message: "Enter a full URL, starting with https://" };
    if (v.badInput || v.typeMismatch) return { field, message: `Enter a valid ${label.toLowerCase()}` };
    if (v.tooShort && el instanceof HTMLInputElement) return { field, message: `${label} needs at least ${el.minLength} characters` };
    if (v.rangeUnderflow && el instanceof HTMLInputElement) return { field, message: `${label} must be at least ${el.min}` };
    if (v.rangeOverflow && el instanceof HTMLInputElement) return { field, message: `${label} must be at most ${el.max}` };
    return { field, message: el.validationMessage || `Check ${label.toLowerCase()}` };
  }
  return null;
}
