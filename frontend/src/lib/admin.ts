/**
 * Admin 模式开关。访问 /admin?token=<USER_TOKEN> 后写 localStorage,
 * 之后的请求会带 X-Auteur-Admin: 1 头。
 *
 * 用 ref 包裹是为了 Vue 响应式追踪 — 直接读 localStorage 在 computed 里跟不到变化。
 */
import { ref, computed } from 'vue'

const KEY = 'auteur_admin'
const OWNER_KEY = 'auteur_owner'

function readAdmin(): boolean {
  try {
    return localStorage.getItem(KEY) === '1'
  } catch {
    return false
  }
}
function readOwner(): string {
  try {
    return localStorage.getItem(OWNER_KEY) ?? ''
  } catch {
    return ''
  }
}

const adminRef = ref<boolean>(readAdmin())
const ownerRef = ref<string>(readOwner())

// 同源标签 / 其它窗口改 localStorage 时同步刷新
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (e) => {
    if (e.key === KEY) adminRef.value = readAdmin()
    if (e.key === OWNER_KEY) ownerRef.value = readOwner()
  })
}

export const adminMode = computed(() => adminRef.value)
export const ownerName = computed(() => ownerRef.value)

export function isAdmin(): boolean {
  return adminRef.value
}

export function getOwnerName(): string {
  return ownerRef.value
}

export function enableAdmin(owner?: string): void {
  localStorage.setItem(KEY, '1')
  adminRef.value = true
  if (owner) {
    localStorage.setItem(OWNER_KEY, owner)
    ownerRef.value = owner
  }
}

export function disableAdmin(): void {
  localStorage.removeItem(KEY)
  localStorage.removeItem(OWNER_KEY)
  adminRef.value = false
  ownerRef.value = ''
}
