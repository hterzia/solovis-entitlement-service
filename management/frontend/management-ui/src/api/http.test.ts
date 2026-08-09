import { describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../test/mocks/server'
import { apiGet, apiPost, ApiError } from './http'

describe('apiGet', () => {
  it('returns parsed JSON on success', async () => {
    server.use(http.get('/admin/v1/ping', () => HttpResponse.json({ ok: true })))
    await expect(apiGet<{ ok: boolean }>('/ping')).resolves.toEqual({ ok: true })
  })

  it('throws an ApiError carrying the problem+json body on failure', async () => {
    server.use(
      http.get('/admin/v1/boom', () =>
        HttpResponse.json(
          { type: 'entitlement/unknown-account', title: 'Unknown account', status: 404, detail: 'No such account.' },
          { status: 404 },
        ),
      ),
    )
    await expect(apiGet('/boom')).rejects.toMatchObject(
      new ApiError({ type: 'entitlement/unknown-account', title: 'Unknown account', status: 404, detail: 'No such account.' }),
    )
  })
})

describe('apiPost', () => {
  it('sends a JSON body and returns the parsed response', async () => {
    server.use(
      http.post('/admin/v1/echo', async ({ request }) => HttpResponse.json(await request.json())),
    )
    await expect(apiPost<{ x: number }>('/echo', { x: 1 })).resolves.toEqual({ x: 1 })
  })
})
