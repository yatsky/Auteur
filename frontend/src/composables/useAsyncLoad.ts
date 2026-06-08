// 异步加载样板:loading + errorMsg + run() —— 把"列表/详情 fetchData"这类高频套路收口。
// 用法:
//   const { loading, errorMsg, run } = useAsyncLoad(() => listTopics(...), { errorPrefix: '加载失败' })
//   onMounted(run)
// 模板里 `loading` / `errorMsg` 仍是 ref,直接用。需要数据时 await run() 取返回值。
import { ref, type Ref } from 'vue'
import { extractError } from '../lib/format'

export interface UseAsyncLoadOptions {
  /** 错误提示前缀,默认 'fetch failed' */
  errorPrefix?: string
  /** 是否在出错后保留旧数据(默认 true,即只刷 errorMsg;false 则在出错时清空) */
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
