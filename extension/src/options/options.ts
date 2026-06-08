import { loadSettings, saveSettings } from '../shared/settings'
import { postSync } from '../shared/syncClient'
import { initTheme, setThemeIcon, toggleTheme } from '../shared/theme'
import { PLATFORMS, type ExtensionSettings } from '../shared/types'

const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T | null

function setStatus(text: string, ok: boolean) {
  const el = $('status')
  if (!el) return
  el.textContent = text
  el.className = ok ? 'saved' : 'err'
  if (ok) setTimeout(() => (el.textContent = ''), 2500)
}

const platformInput = (p: string) =>
  document.querySelector<HTMLInputElement>(`input[data-platform="${p}"]`)

function readForm(): ExtensionSettings {
  const enabled = {} as ExtensionSettings['enabled']
  PLATFORMS.forEach((p) => (enabled[p] = platformInput(p)?.checked ?? false))
  return {
    backendUrl: $<HTMLInputElement>('backendUrl')!.value.trim(),
    token: $<HTMLInputElement>('token')!.value.trim(),
    enabled,
  }
}

function fillForm(s: ExtensionSettings) {
  $<HTMLInputElement>('backendUrl')!.value = s.backendUrl
  $<HTMLInputElement>('token')!.value = s.token
  PLATFORMS.forEach((p) => {
    const cb = platformInput(p)
    if (cb) cb.checked = !!s.enabled[p]
  })
}

async function ping(s: ExtensionSettings) {
  try {
    await postSync([], s)
    setStatus('连通正常(HTTP 2xx)', true)
  } catch (e) {
    setStatus((e as Error).message.slice(0, 100), false)
  }
}

document.addEventListener('DOMContentLoaded', async () => {
  const themeIcon = $('themeIcon')
  setThemeIcon(themeIcon, await initTheme((t) => setThemeIcon(themeIcon, t)))
  fillForm(await loadSettings())

  $('themeBtn')?.addEventListener('click', async () => setThemeIcon(themeIcon, await toggleTheme()))

  $('saveBtn')?.addEventListener('click', async () => {
    const next = readForm()
    if (!next.backendUrl) return setStatus('后端地址必填', false)
    await saveSettings(next)
    setStatus('已保存', true)
  })

  $('testBtn')?.addEventListener('click', async () => {
    const cur = readForm()
    if (!cur.backendUrl || !cur.token) return setStatus('请先填后端地址与 token', false)
    try {
      await ping(cur)
    } catch (e) {
      setStatus(`请求失败: ${(e as Error).message}`, false)
    }
  })
})
