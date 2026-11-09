/**
 * A one-shot message carried across a navigation ("Registered Doc summariser"), shown once by
 * <FlashBanner> on the next page and then cleared. sessionStorage keeps it per tab.
 */
const KEY = "hireai.flash";

export function setFlash(message: string): void {
  try {
    sessionStorage.setItem(KEY, message);
  } catch {
    /* storage unavailable: the user just doesn't get the banner */
  }
}

export function takeFlash(): string | null {
  try {
    const m = sessionStorage.getItem(KEY);
    if (m) sessionStorage.removeItem(KEY);
    return m;
  } catch {
    return null;
  }
}
