import { DEFAULT_SETTINGS, PLATFORMS, type ExtensionSettings, type Platform, type RuntimeStatus } from './types'

export const SETTINGS_KEY = 'settings'
export const STATUS_KEY = 'runtime.status'

const EMPTY_PLATFORMS = PLATFORMS.reduce(
  (m, p) => ((m[p] = null), m),
  {} as Record<Platform, number | null>,
)

export async function loadSettings(): Promise<ExtensionSettings> {
  const r = await chrome.storage.local.get(SETTINGS_KEY)
  const stored = r[SETTINGS_KEY] as Partial<ExtensionSettings> | undefined
  return {
    ...DEFAULT_SETTINGS,
    ...stored,
    enabled: { ...DEFAULT_SETTINGS.enabled, ...stored?.enabled },
  }
}

export async function saveSettings(s: ExtensionSettings): Promise<void> {
  await chrome.storage.local.set({ [SETTINGS_KEY]: s })
}

export async function loadStatus(): Promise<RuntimeStatus> {
  const r = await chrome.storage.local.get(STATUS_KEY)
  const stored = r[STATUS_KEY] as Partial<RuntimeStatus> | undefined
  return {
    lastSentAt: stored?.lastSentAt ?? null,
    lastError: stored?.lastError ?? null,
    platforms: { ...EMPTY_PLATFORMS, ...stored?.platforms },
  }
}

export async function patchStatus(p: Partial<RuntimeStatus>): Promise<void> {
  const cur = await loadStatus()
  await chrome.storage.local.set({ [STATUS_KEY]: { ...cur, ...p } })
}
