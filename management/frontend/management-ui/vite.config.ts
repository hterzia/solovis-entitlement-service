/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The backend the dev server proxies to. Overridable so the end-to-end run can point at its own
// throwaway service on its own port without colliding with a backend someone is already running on
// 8081 for day-to-day work.
const apiTarget = process.env.ENTITLEMENT_API_URL ?? 'http://localhost:8081'

export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    proxy: {
      '/admin': apiTarget,
      '/v1': apiTarget,
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    globals: true,
    // e2e/ is Playwright's, and its specs need a real browser and a real backend. Vitest's default
    // include would pick them up as jsdom tests and fail on the first `page` fixture.
    exclude: ['**/node_modules/**', '**/dist/**', 'e2e/**'],
  },
})
