import { BROWSER_MODEL_ARTIFACT, BROWSER_MODEL_REVISION, normalizeEmbedding } from './embedding'

type Request = { text: string; dimensions: number }
let engine: Promise<{ model: any; tokenizer: any }> | undefined

async function loadEngine() {
  if (!engine) {
    engine = (async () => {
      const { AutoConfig, AutoModel, AutoTokenizer } = await import('@huggingface/transformers')
      const options = { revision: BROWSER_MODEL_REVISION }
      const config = await AutoConfig.from_pretrained(BROWSER_MODEL_ARTIFACT, options)
      // Exclude multimodal encoders: only text search is supported here.
      const textOnlyConfig = config as typeof config & { vision_config: null; audio_config: null }
      textOnlyConfig.vision_config = null
      textOnlyConfig.audio_config = null
      const [model, tokenizer] = await Promise.all([
        AutoModel.from_pretrained(BROWSER_MODEL_ARTIFACT, {
          ...options,
          config,
          device: 'webgpu',
          dtype: 'q4',
        }),
        AutoTokenizer.from_pretrained(BROWSER_MODEL_ARTIFACT, options),
      ])
      return { model, tokenizer }
    })().catch((error) => {
      engine = undefined
      throw error
    })
  }
  return engine
}

self.onmessage = async (event: MessageEvent<Request>) => {
  try {
    const { model, tokenizer } = await loadEngine()
    const output = await model(await tokenizer([event.data.text]))
    const vector = normalizeEmbedding(output.sentence_embedding.data, event.data.dimensions)
    self.postMessage({ type: 'result', vector })
  } catch (error) {
    self.postMessage({ type: 'error', message: error instanceof Error ? error.message : String(error) })
  }
}
