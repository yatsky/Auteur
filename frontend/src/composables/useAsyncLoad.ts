import { ref, type Ref } from 'vue'
import { extractError } from '../lib/format'

export interface UseAsyncLoadOptions {
  errorPrefix?: string
  /** 是否在出错后保留旧数据(默认 true) */
  keepOnError?: boolean
}

export function useAsyncLoad<T = void>(
  fn: () => Promise<T>,
  opts: UseAsyncLoadOptions = {},
) {
  const loading = ref(false)
  const errorMsg = ref<string | null>(null)
  const data: Ref<T | null> = ref(null) as Ref<T | null>
  const prefix = opts.errorPrefix ?? 'fetch failed'

  async function run(): Promise<T | null> {
    loading.value = true
    errorMsg.value = null
    try {
      const r = await fn()
      data.value = r
      return r
    } catch (e) {
      errorMsg.value = extractError(e, prefix)
      if (opts.keepOnError === false) data.value = null
      return null
    } finally {
      loading.value = false
    }
  }

  return { loading, errorMsg, data, run, retry: run }
}
