export const queryKeys = {
  meta: ['meta'] as const,
  capabilities: (params?: { area?: string; q?: string; status?: string }) => ['capabilities', params ?? {}] as const,
  capability: (key: string) => ['capabilities', key] as const,
  plans: () => ['plans'] as const,
  plan: (key: string) => ['plans', key] as const,
  accounts: (params?: { q?: string; planKey?: string; cursor?: string }) => ['accounts', params ?? {}] as const,
  account: (external: string) => ['accounts', external] as const,
  check: (params: { account?: string; capability?: string; override?: string }) => ['check', params] as const,
  audit: (params: Record<string, string | undefined>) => ['audit', params] as const,
}
