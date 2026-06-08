// 列表搜索 + 排序的通用 composable。
// 不管分页 —— 分页留在 view 里(因为通常涉及后端 page 参数,跟前端纯过滤是两回事)。
import { computed, ref, type ComputedRef, type Ref } from 'vue'

export interface UseListFilterOptions<T> {
  /** 数据源(响应式) */
  source: Ref<T[]> | ComputedRef<T[]>
  /** 用关键词过滤的字段提取函数,返回字符串数组(关键词命中任一即匹配) */
  searchableText?: (item: T) => string[]
  /** 排序选项的 key → compareFn 映射 */
  sortOptions?: Record<string, (a: T, b: T) => number>
  /** 默认排序 key */
  defaultSort?: string
}

export function useListFilter<T>(opts: UseListFilterOptions<T>) {
  const keyword = ref('')
  const sortKey = ref(opts.defaultSort ?? '')

  const filtered = computed(() => {
    const k = keyword.value.trim().toLowerCase()
    let arr = opts.source.value
    if (k && opts.searchableText) {
      arr = arr.filter((item) =>
        opts.searchableText!(item).some((t) => (t ?? '').toLowerCase().includes(k)),
      )
    }
    if (sortKey.value && opts.sortOptions?.[sortKey.value]) {
      arr = [...arr].sort(opts.sortOptions[sortKey.value])
    }
    return arr
  })

  return { keyword, sortKey, filtered }
}
