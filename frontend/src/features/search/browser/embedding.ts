import type { Json } from '../../../api'

export const BROWSER_MODEL = 'google/embeddinggemma-2'
export const BROWSER_MODEL_ARTIFACT = 'onnx-community/embeddinggemma-2-ONNX'
export const BROWSER_MODEL_REVISION = 'daa72c51243991dfcaf9f9137d2c573d8f7790c0'

export type BrowserModel = { id: string; model: string; dimensions: 512 | 768 }

// This catalogue describes artifacts shipped by the application, not server profiles.
export const BROWSER_MODELS: readonly BrowserModel[] = [
  { id: 'embeddinggemma-2-512', model: BROWSER_MODEL, dimensions: 512 },
  { id: 'embeddinggemma-2-768', model: BROWSER_MODEL, dimensions: 768 },
]

export function compatibleBrowserModels(indexProfile: Json | undefined): BrowserModel[] {
  if (
    !indexProfile ||
    indexProfile.preparation?.version !== 'identity-v1' ||
    typeof indexProfile.preparation?.query_prefix !== 'string'
  )
    return []
  return BROWSER_MODELS.filter(
    (model) => model.model === indexProfile.model && model.dimensions === indexProfile.dimensions,
  )
}

export function prepareBrowserQuery(profile: Json, text: string): string {
  if (
    profile.preparation?.version !== 'identity-v1' ||
    typeof profile.preparation?.query_prefix !== 'string'
  ) {
    throw new Error('Unsupported query preparation')
  }
  if (!text.trim()) throw new Error('Empty query')
  return profile.preparation.query_prefix + text
}

export function normalizeEmbedding(values: ArrayLike<number>, dimensions: number): number[] {
  if ((dimensions !== 512 && dimensions !== 768) || values.length !== 768)
    throw new Error('Unexpected embedding dimensions')
  const result = Array.from({ length: dimensions }, (_, i) => Number(values[i]))
  if (result.some((v) => !Number.isFinite(v))) throw new Error('Invalid embedding')
  const norm = Math.hypot(...result)
  if (!Number.isFinite(norm) || norm === 0) throw new Error('Invalid embedding norm')
  return result.map((v) => v / norm)
}
