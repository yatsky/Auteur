// useBrandIdentity —— 账号级品牌包,后端 brand_identity 单行表(id=1)。
// 模块级 ref:onMounted GET 一次,所有引用页面共享;watch debounce 写回 PUT。
// 一次性迁移:首屏 GET 回来若仍是初始空值且 localStorage 有旧 mock 数据,PUT 上去当迁移。
import { ref, watch } from 'vue'
import type { BrandIdentity } from '../types'
import { getBrandIdentity, saveBrandIdentity } from '../api/scripts'

const LEGACY_STORAGE_KEY = 'auteur.brand'

// 默认品牌包 —— "历史密档" 频道的墨蓝 + 古铜金 + 印章红配色。仅在首次后端 GET 完成前作占位。
const DEFAULT_BRAND: BrandIdentity = {
  brandName: '历史密档',
  authorName: '',
  logoDataUrl: null,
  primaryColor: '#1A1A2E',
  secondaryColor: '#C9A86B',
  accentColor: '#8B1A1A',
  bgColor: '#F5EFE0',
  titleFont: '"Noto Serif SC", "PingFang SC", serif',
  defaultTemplateId: 'bottom-caption',
  updatedAt: new Date().toISOString(),
}

const brand = ref<BrandIdentity>({ ...DEFAULT_BRAND })

// 加载状态:loaded=true 之前 watch 不触发 PUT,避免初始默认值覆盖后端真值
const loaded = ref(false)
const loading = ref(false)
let loadPromise: Promise<void> | null = null

function readLegacyLocalStorage(): Partial<BrandIdentity> | null {
  try {
    const raw = localStorage.getItem(LEGACY_STORAGE_KEY)
    if (!raw) return null
    return JSON.parse(raw) as Partial<BrandIdentity>
  } catch {
    return null
  }
}

function isBackendEmpty(b: BrandIdentity): boolean {
  // 后端首次启动 INSERT 的行 brand_name='' author_name='',颜色字段在 entity 给了默认值
  // 但 logoDataUrl 为 null,brandName/authorName 空 → 算"空"
  return (!b.brandName || b.brandName.trim() === '')
      && (!b.authorName || b.authorName.trim() === '')
      && !b.logoDataUrl
}

async function ensureLoaded() {
  if (loaded.value) return
  if (loadPromise) return loadPromise
  loading.value = true
  loadPromise = (async () => {
    try {
      const remote = await getBrandIdentity()
      // 一次性迁移:后端空 + localStorage 有旧 mock → PUT 上去
      if (isBackendEmpty(remote)) {
        const legacy = readLegacyLocalStorage()
        if (legacy && (legacy.brandName || legacy.authorName || legacy.logoDataUrl)) {
          try {
            const merged = { ...remote, ...legacy } as BrandIdentity
            const migrated = await saveBrandIdentity(merged)
            brand.value = { ...DEFAULT_BRAND, ...migrated }
            localStorage.removeItem(LEGACY_STORAGE_KEY)
            loaded.value = true
            return
          } catch (e) {
            console.warn('[useBrandIdentity] migrate failed, falling back to remote', e)
          }
        }
        // 没旧数据可迁:把 DEFAULT_BRAND 的展示值合进 remote(后端字段为 hex 默认色,brandName/authorName 留空)
        brand.value = { ...DEFAULT_BRAND, ...remote }
      } else {
        brand.value = { ...DEFAULT_BRAND, ...remote }
      }
      loaded.value = true
    } catch (e) {
      console.warn('[useBrandIdentity] load failed, using defaults', e)
      // 后端不可达:不进入 loaded=true,后续 watch 不会 PUT,刷新再试
    } finally {
      loading.value = false
    }
  })()
  return loadPromise
}

// debounce PUT —— 用户调色滑块快速变化时合并写
let saveTimer: ReturnType<typeof setTimeout> | null = null
let lastSaveSerialized: string = ''

watch(brand, (next) => {
  if (!loaded.value) return  // 还没拉过 → 不要写
  const serialized = JSON.stringify({
    brandName: next.brandName,
    authorName: next.authorName,
    logoDataUrl: next.logoDataUrl,
    primaryColor: next.primaryColor,
    secondaryColor: next.secondaryColor,
    accentColor: next.accentColor,
    bgColor: next.bgColor,
    titleFont: next.titleFont,
    defaultTemplateId: next.defaultTemplateId,
  })
  if (serialized === lastSaveSerialized) return
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(async () => {
    try {
      const saved = await saveBrandIdentity(next)
      lastSaveSerialized = serialized
      // 把 updatedAt 同步回来,但不要用整个 saved 覆盖(避免触发再次 watch)
      brand.value.updatedAt = saved.updatedAt
    } catch (e) {
      console.warn('[useBrandIdentity] save failed', e)
    }
  }, 600)
}, { deep: true })

export function useBrandIdentity() {
  // 首次调用立即拉一次。重复调不会重复请求(loadPromise 复用)
  void ensureLoaded()
  return {
    brand,
    loaded,
    loading,
    async refresh() {
      loaded.value = false
      loadPromise = null
      await ensureLoaded()
    },
    reset() {
      brand.value = { ...DEFAULT_BRAND, updatedAt: new Date().toISOString() }
    },
  }
}

export const BRAND_FONT_OPTIONS = [
  { value: '"Noto Serif SC", "PingFang SC", serif', label: '思源宋体(Noto Serif SC)' },
  { value: '"PingFang SC", "Microsoft YaHei", sans-serif', label: '苹方 PingFang SC' },
  { value: '"ZCOOL KuaiLe", "PingFang SC", sans-serif', label: '站酷快乐体' },
  { value: '"ZCOOL XiaoWei", "PingFang SC", serif', label: '站酷小薇' },
  { value: '"Source Han Serif", "Noto Serif SC", serif', label: '思源宋体 Source Han' },
]
