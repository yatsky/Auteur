// useTheme —— 全局主题切换,持久化到 localStorage.auteur.theme
// 存的是"用户偏好"(dark/light/auto),实际生效的 effective 主题在 auto 时跟随 prefers-color-scheme
// 默认深色(.light 类不挂);切到浅色时给 <html> 加 .light 类,Tailwind 的 CSS 变量在 style.css 里跟着变
import { computed, ref, watch } from 'vue'

export type Theme = 'dark' | 'light'
export type ThemePref = Theme | 'auto'

const STORAGE_KEY = 'auteur.theme'

function readInitialPref(): ThemePref {
  const v = localStorage.getItem(STORAGE_KEY)
  return v === 'light' || v === 'auto' ? v : 'dark'
}

function systemPrefers(): Theme {
  if (typeof window === 'undefined' || !window.matchMedia) return 'dark'
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

function apply(t: Theme) {
  const html = document.documentElement
  if (t === 'light') html.classList.add('light')
  else html.classList.remove('light')
}

const pref = ref<ThemePref>(readInitialPref())
const systemTheme = ref<Theme>(systemPrefers())
const theme = computed<Theme>(() => (pref.value === 'auto' ? systemTheme.value : pref.value))

apply(theme.value)

watch(pref, (p) => {
  localStorage.setItem(STORAGE_KEY, p)
})
watch(theme, (t) => {
  apply(t)
})

// 监听系统主题变化,只在 pref=auto 时通过 computed 间接影响 effective theme
if (typeof window !== 'undefined' && window.matchMedia) {
  const mq = window.matchMedia('(prefers-color-scheme: dark)')
  const onChange = (e: MediaQueryListEvent) => {
    systemTheme.value = e.matches ? 'dark' : 'light'
  }
  if (mq.addEventListener) mq.addEventListener('change', onChange)
  else (mq as MediaQueryList & { addListener: (cb: (e: MediaQueryListEvent) => void) => void }).addListener(onChange)
}

export function useTheme() {
  return {
    theme,
    pref,
    toggle() {
      // sidebar 按钮:基于当前 effective 翻转,固化为显式 dark/light(覆盖 auto)
      pref.value = theme.value === 'dark' ? 'light' : 'dark'
    },
    set(p: ThemePref) {
      pref.value = p
    },
  }
}
