import { setupServer } from 'msw/node'
import { handlers, resetDb } from './handlers'

export const server = setupServer(...handlers)
export { resetDb }
