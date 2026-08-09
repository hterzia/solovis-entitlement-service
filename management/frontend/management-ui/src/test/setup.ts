import '@testing-library/jest-dom/vitest'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { server, resetDb } from './mocks/server'

// jsdom doesn't implement scrollTo, and TanStack Router's RouterProvider (mounted by
// renderWithProviders from Task 10 onward) calls it on every route match — without this stub,
// every test using renderWithProviders prints "Not implemented: Window's scrollTo() method".
window.scrollTo = () => {}

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  resetDb()
})
afterAll(() => server.close())
