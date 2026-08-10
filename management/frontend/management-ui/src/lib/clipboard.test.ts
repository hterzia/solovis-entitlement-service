import { afterEach, describe, expect, test, vi } from 'vitest'
import { copyText } from './clipboard'

/**
 * The operator console is served over plain HTTP on a LAN host, so `navigator.clipboard` — which
 * only exists in a secure context — is `undefined` in the deployment that matters. Before this
 * helper existed, "Copy explanation" threw `Cannot read properties of undefined (reading
 * 'writeText')` on every non-localhost origin, which is every real one.
 */

const originalClipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard')

function setClipboard(value: unknown) {
  Object.defineProperty(navigator, 'clipboard', { value, configurable: true, writable: true })
}

afterEach(() => {
  if (originalClipboard) Object.defineProperty(navigator, 'clipboard', originalClipboard)
  else setClipboard(undefined)
  vi.restoreAllMocks()
  // @ts-expect-error — execCommand is not in the jsdom typings we stub onto
  delete document.execCommand
})

describe('copyText', () => {
  test('uses the async clipboard API when the page is in a secure context', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    setClipboard({ writeText })

    await expect(copyText('the explanation')).resolves.toBe(true)
    expect(writeText).toHaveBeenCalledWith('the explanation')
  })

  test('falls back to a selection copy when navigator.clipboard is absent', async () => {
    setClipboard(undefined)
    const execCommand = vi.fn().mockReturnValue(true)
    document.execCommand = execCommand

    await expect(copyText('the explanation')).resolves.toBe(true)
    expect(execCommand).toHaveBeenCalledWith('copy')
  })

  test('falls back to a selection copy when the async clipboard API rejects', async () => {
    setClipboard({ writeText: vi.fn().mockRejectedValue(new Error('denied')) })
    const execCommand = vi.fn().mockReturnValue(true)
    document.execCommand = execCommand

    await expect(copyText('the explanation')).resolves.toBe(true)
    expect(execCommand).toHaveBeenCalledWith('copy')
  })

  test('reports failure rather than throwing when no copy mechanism works', async () => {
    setClipboard(undefined)

    await expect(copyText('the explanation')).resolves.toBe(false)
  })

  test('leaves no scratch node behind in the document', async () => {
    setClipboard(undefined)
    document.execCommand = vi.fn().mockReturnValue(true)

    await copyText('the explanation')

    expect(document.querySelectorAll('textarea')).toHaveLength(0)
  })
})
