import { FRESH_WINDOW_MS } from '../shared/constants'
import { loadStatus, STATUS_KEY } from '../shared/settings'
import { initTheme, setThemeIcon, toggleTheme } from '../shared/theme'
import type { Platform, RuntimeStatus } from '../shared/types'

const PLATFORM_URLS: Record<Platform, string> = {
  抖音:  'https://creator.douyin.com/creator-micro/data/following',
  B站:   'https://member.bilibili.com/platform/data-center/data-up',
  视频号: 'https://channels.weixin.qq.com/platform/post/list',
  快手:  'https://cp.kuaishou.com/profile?tab=videoAnalyse',
}

type Freshness = 'done' | 'stale' | 'never'

function freshnessOf(ts: number | null): Freshness {
  if (!ts) return 'never'
  return Date.now() - ts < FRESH_WINDOW_MS ? 'done' : 'stale'
}

function fmtRelTime(ts: number | null): string {
  if (!ts) return '—'
  const s = (Date.now() - ts) / 1000
  if (s < 60) return `${s | 0} 秒前`
  if (s < 3600) return `${(s / 60) | 0} 分钟前`
  if (s < 86400) return `${(s / 3600) | 0} 小时前`
  if (s < 86400 * 30) return `${(s / 86400) | 0} 天前`
  return new Date(ts).toLocaleDateString()
}

function renderPlatforms({ platforms }: RuntimeStatus) {
  const root = document.getElementById('platforms')
  if (!root) return
  root.innerHTML = ''
  for (const name of Object.keys(PLATFORM_URLS) as Platform[]) {
    const ts = platforms[name] ?? null
    const f = freshnessOf(ts)
    const url = PLATFORM_URLS[name]

    const li = document.createElement('li')
    li.className = 'platform'
    li.innerHTML = `
      <span class="dot ${f}"></span>
      <div class="meta">
        <span class="name">${name}</span>
        <span class="time ${f}">${f === 'never' ? '未采集' : fmtRelTime(ts)}</span>
      </div>
      <button class="go-btn">${f === 'done' ? '再采一次' : '前往采集'}</button>
    `
    li.addEventListener('click', () => chrome.tabs.create({ url }))
    root.appendChild(li)
  }
}

function renderFooter({ lastSentAt, lastError }: RuntimeStatus) {
  const lastSent = document.getElementById('lastSent')
  if (lastSent) lastSent.textContent = fmtRelTime(lastSentAt)

  const errBox = document.getElementById('errBox')
  if (!errBox) return
  errBox.innerHTML = lastError ? `<div class="err">${lastError}</div>` : ''
}

async function render() {
  const s = await loadStatus()
  renderPlatforms(s)
  renderFooter(s)
}

document.addEventListener('DOMContentLoaded', async () => {
  const themeIcon = document.getElementById('themeIcon')
  setThemeIcon(themeIcon, await initTheme((t) => setThemeIcon(themeIcon, t)))
  render()

  document.getElementById('optionsBtn')?.addEventListener('click', () => chrome.runtime.openOptionsPage())
  document.getElementById('themeBtn')?.addEventListener('click', async () => setThemeIcon(themeIcon, await toggleTheme()))
  document.getElementById('refreshBtn')?.addEventListener('click', render)
  chrome.storage.onChanged.addListener((c, area) => {
    if (area === 'local' && c[STATUS_KEY]) render()
  })
})
