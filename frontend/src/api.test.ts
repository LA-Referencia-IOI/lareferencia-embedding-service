import { describe, it, expect, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from './test/setup'
import { ApiError, key, query, request } from './api'

describe('API client', () => {
  it('creates a cryptographically random UUID when randomUUID is unavailable over HTTP', () => {
    vi.stubGlobal('crypto', {
      getRandomValues: (bytes: Uint8Array) => bytes.fill(0xff),
    })
    try {
      expect(key()).toBe('ffffffff-ffff-4fff-bfff-ffffffffffff')
    } finally {
      vi.unstubAllGlobals()
    }
  })
  it('encodes identifiers and skips optional parameters', () => {
    expect(query({ external_id: 'a: b/ç', collection_id: '', limit: 25 })).toBe(
      '?external_id=a%3A+b%2F%C3%A7&limit=25',
    )
  })
  it('sends conditional and idempotency headers and exposes the response ETag', async () => {
    server.use(
      http.patch('http://localhost:8989/api', async ({ request: req }) => {
        expect(req.headers.get('if-match')).toBe('"2"')
        expect(req.headers.get('idempotency-key')).toBe('same-key')
        expect(await req.json()).toEqual({ x: 1 })
        return HttpResponse.json({ id: 'a' }, { headers: { ETag: '"3"' } })
      }),
    )
    const result = await request('http://localhost:8989', '/api', {
      method: 'PATCH',
      body: { x: 1 },
      etag: '"2"',
      key: 'same-key',
    })
    expect(result.etag).toBe('"3"')
  })
  it('preserves API errors and details', async () => {
    server.use(
      http.get('http://localhost:8989/api', () =>
        HttpResponse.json(
          { code: 'ambiguous_external_id', message: 'ambiguous', details: [{ field: 'external_id' }] },
          { status: 409 },
        ),
      ),
    )
    const err = await request('http://localhost:8989', '/api').catch((e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err.code).toBe('ambiguous_external_id')
    expect(err.details).toEqual([{ field: 'external_id' }])
  })
  it('allows browser to set multipart boundary', async () => {
    server.use(
      http.post('http://localhost:8989/api', ({ request: req }) => {
        expect(req.headers.get('content-type')).toContain('multipart/form-data; boundary=')
        return HttpResponse.json({})
      }),
    )
    await request('http://localhost:8989', '/api', { method: 'POST', body: new FormData() })
  })
})
