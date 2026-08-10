export const queryKeys = {
  meta: ['meta'] as const,
  capabilities: (params?: { area?: string; q?: string; status?: string }) => ['capabilities', params ?? {}] as const,
  capability: (key: string) => ['capabilities', key] as const,
  plans: () => ['plans'] as const,
  plan: (key: string) => ['plans', key] as const,
  accounts: (params?: { q?: string; planKey?: string; cursor?: string }) => ['accounts', params ?? {}] as const,
  /**
   * Deliberately not `accounts({ q })`: the accounts screen holds that key with a
   * `useInfiniteQuery`, whose cached shape is `{ pages, pageParams }` rather than the
   * `{ accounts, nextCursor }` a plain `useQuery` expects. Sharing it would hand one observer
   * the other's shape. Still under the `['accounts']` prefix, so creating an account
   * invalidates these suggestions too.
   */
  accountSuggestions: (q: string) => ['accounts', 'suggest', q] as const,
  account: (external: string) => ['accounts', external] as const,
  check: (params: { account?: string; capability?: string; override?: string; asAt?: string }) => ['check', params] as const,
  overrideRemovalPreview: (external: string, id: string) => ['accounts', external, 'overrides', id, 'removal-preview'] as const,
  audit: (params: Record<string, string | undefined>) => ['audit', params] as const,
}
