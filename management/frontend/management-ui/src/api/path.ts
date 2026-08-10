/**
 * Encode a value being interpolated into a request path.
 *
 * Capability keys, plan keys, account external ids and override refs all reach the API as path
 * segments. Left raw, one containing `/`, `#` or `?` either addresses a different resource than
 * intended or produces a malformed request — silently, because the request still succeeds in
 * looking reasonable.
 */
export function enc(segment: string): string {
  return encodeURIComponent(segment)
}
