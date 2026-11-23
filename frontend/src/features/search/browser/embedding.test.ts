import { describe, expect, it } from 'vitest'
import { compatibleBrowserModels, normalizeEmbedding, prepareBrowserQuery } from './embedding'

const base = {
  model: 'google/embeddinggemma-2',
  model_revision: 'revision-1',
  dimensions: 512,
  preparation: { version: 'identity-v1', query_prefix: 'task: search | query: ' },
}
describe('browser embeddings', () => {
  it('lists only the browser model that matches the index model, dimension and preparation', () => {
    expect(compatibleBrowserModels(base)).toEqual([
      { id: 'embeddinggemma-2-512', model: base.model, dimensions: 512 },
    ])
    expect(compatibleBrowserModels({ ...base, dimensions: 768 })[0].dimensions).toBe(768)
    expect(compatibleBrowserModels({ ...base, model: 'other' })).toEqual([])
    expect(compatibleBrowserModels({ ...base, dimensions: 256 })).toEqual([])
    expect(
      compatibleBrowserModels({
        ...base,
        preparation: { version: 'legacy-normalizer-v1', query_prefix: '' },
      }),
    ).toEqual([])
    expect(compatibleBrowserModels(undefined)).toEqual([])
  })
  it('applies the index profile query prefix without changing the text', () => {
    expect(prepareBrowserQuery(base, 'Query Á')).toBe('task: search | query: Query Á')
  })
  it('truncates and renormalizes 512 and 768 dimensions', () => {
    const input = Array.from({ length: 768 }, (_, i) => (i === 0 || i === 600 ? 3 : 0))
    const v512 = normalizeEmbedding(input, 512)
    const v768 = normalizeEmbedding(input, 768)
    expect(v512).toHaveLength(512)
    expect(v512[0]).toBe(1)
    expect(v768).toHaveLength(768)
    expect(v768[0]).toBeCloseTo(Math.SQRT1_2)
    expect(Math.hypot(...v768)).toBeCloseTo(1)
    expect(() => normalizeEmbedding([1, 2], 512)).toThrow()
    expect(() => normalizeEmbedding(Array(768).fill(0), 768)).toThrow()
  })
})
