/**
 * Copy text to the clipboard, working on the origins this console is actually served from.
 *
 * `navigator.clipboard` exists only in a secure context. The management app runs over plain HTTP on
 * a LAN host, so on every deployment that matters the async API is simply `undefined` and reaching
 * for it throws. The selection-based fallback is the one mechanism that still works there.
 *
 * Returns whether the copy succeeded rather than throwing, so callers can tell the operator the
 * truth instead of silently doing nothing.
 */
export async function copyText(text: string): Promise<boolean> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // Permission denied or a non-secure context that still exposes the object — fall through.
    }
  }
  return selectionCopy(text)
}

function selectionCopy(text: string): boolean {
  const scratch = document.createElement('textarea')
  scratch.value = text
  scratch.setAttribute('readonly', '')
  // Kept in the viewport but invisible: `display: none` would make it unselectable, and scrolling
  // the page under the operator to reach an off-screen node is worse than an invisible one.
  scratch.style.position = 'fixed'
  scratch.style.top = '0'
  scratch.style.opacity = '0'
  document.body.appendChild(scratch)
  try {
    scratch.select()
    return document.execCommand?.('copy') ?? false
  } catch {
    return false
  } finally {
    document.body.removeChild(scratch)
  }
}
