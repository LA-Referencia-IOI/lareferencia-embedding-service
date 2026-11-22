export type Json = Record<string, any>
export class ApiError extends Error {
  status: number
  code: string
  details: unknown
  constructor(status: number, code: string, message: string, details: unknown = []) {
    super(message)
    this.status = status
    this.code = code
    this.details = details
  }
}
export const query = (values: Record<string, string | number | undefined>) => {
  const params = new URLSearchParams()
  Object.entries(values).forEach(([key, value]) => {
    if (value !== undefined && value !== '') params.set(key, String(value))
  })
  const result = params.toString()
  return result ? `?${result}` : ''
}
export async function request(
  base: string,
  path: string,
  options: { method?: string; body?: unknown; etag?: string; key?: string; signal?: AbortSignal } = {},
) {
  const headers = new Headers({ Accept: 'application/json' })
  const method = options.method ?? 'GET'
  if (options.etag) headers.set('If-Match', options.etag)
  if (options.key) headers.set('Idempotency-Key', options.key)
  let body: BodyInit | undefined
  if (options.body instanceof FormData) body = options.body
  else if (options.body !== undefined) {
    headers.set('Content-Type', 'application/json')
    body = JSON.stringify(options.body)
  }
  const response = await fetch(`${base.replace(/\/$/, '')}${path}`, {
    method,
    headers,
    body,
    signal: options.signal,
  })
  const raw = await response.text()
  let data: any = null
  if (raw) {
    try {
      data = JSON.parse(raw)
    } catch {
      data = raw
    }
  }
  if (!response.ok)
    throw new ApiError(
      response.status,
      data?.code || 'request_failed',
      data?.message || response.statusText,
      data?.details,
    )
  return {
    data,
    etag: response.headers.get('ETag') || '',
    location: response.headers.get('Location') || '',
    status: response.status,
  }
}
export const key = () => {
  if (typeof globalThis.crypto.randomUUID === 'function') return globalThis.crypto.randomUUID()
  const bytes = globalThis.crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
export const enc = encodeURIComponent
