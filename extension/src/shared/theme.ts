export type Theme = 'dark' | 'light'

const KEY = 'auteur.theme'

const apply = (t: Theme) => document.documentElement.classList.toggle('light', t === 'light')

async function loadPref(): Promise<Theme> {
  const r = await chrome.storage.local.get(KEY)
  return r[KEY] === 'light' ? 'light' : 'dark'
}

const SUN =
  '<circle cx="12" cy="12" r="4"/>' +
  '<path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41"/>'
const MOON = '<path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/>'

/** dark 时画太阳(点击切到亮),light 时画月亮(点击切到暗)。 */
export function setThemeIcon(el: Element | null, t: Theme): void {
  if (el) el.innerHTML = t === 'dark' ? SUN : MOON
}

/** 应用偏好 + 订阅 storage 变更,让 popup/options 同时打开时彼此同步。 */
export async function initTheme(onChange?: (t: Theme) => void): Promise<Theme> {
  const cur = await loadPref()
  apply(cur)
  chrome.storage.onChanged.addListener((c, area) => {
    if (area !== 'local' || !c[KEY]) return
    const next = c[KEY].newValue === 'light' ? 'light' : 'dark'
    apply(next)
    onChange?.(next)
  })
  return cur
}

export async function toggleTheme(): Promise<Theme> {
  const next: Theme = (await loadPref()) === 'dark' ? 'light' : 'dark'
  await chrome.storage.local.set({ [KEY]: next })
  return next
}
