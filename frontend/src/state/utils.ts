import type { Json } from '../api'
export const asArray = (data: any): Json[] =>
  Array.isArray(data) ? data : Array.isArray(data?.items) ? data.items : []
export const pretty = (data: unknown) => JSON.stringify(data, null, 2)
export const field = (form: FormData, name: string) => String(form.get(name) ?? '')
export const lines = (s: string) =>
  s
    .split(',')
    .map((x) => x.trim())
    .filter(Boolean)
