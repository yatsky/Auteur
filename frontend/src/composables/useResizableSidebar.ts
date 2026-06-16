/**
 * 可拖拽宽度的侧边栏 composable。
 *
 * side='left' 侧栏在左,拖手在右边(向右拖变宽);'right' 反之。
 */
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'

export interface ResizableSidebarOptions {
  storageKey: string
  defaultWidth: number
  minWidth: number
  maxWidth: number
  collapseAtWidth: number
  /** 折叠后窄条的宽度(px),默认 36(对应 Tailwind w-9)。 */
  collapsedStripWidth?: number
  side: 'left' | 'right'
}

export function useResizableSidebar(opts: ResizableSidebarOptions) {
  const width = ref(opts.defaultWidth)
  const collapsed = ref(false)
  const dragging = ref(false)

  const widthKey = opts.storageKey + '.width'
  const collapsedKey = opts.storageKey + '.collapsed'
  const stripW = opts.collapsedStripWidth ?? 36

  onMounted(() => {
    const wRaw = localStorage.getItem(widthKey)
    if (wRaw) {
      const n = parseInt(wRaw, 10)
      if (!isNaN(n)) {
        width.value = Math.max(opts.minWidth, Math.min(opts.maxWidth, n))
      }
    }
    if (localStorage.getItem(collapsedKey) === '1') collapsed.value = true
  })

  watch(width, (v) => {
    localStorage.setItem(widthKey, String(Math.round(v)))
  })

  watch(collapsed, (v) => {
    localStorage.setItem(collapsedKey, v ? '1' : '0')
  })

  let cleanup: (() => void) | null = null

  function startDrag(e: MouseEvent) {
    e.preventDefault()
    dragging.value = true
    const startX = e.clientX
    const wasCollapsed = collapsed.value
    // 折叠态:从窄条宽度开始算,这样向外拖出 next = stripW + delta 与视觉一致
    const startW = wasCollapsed ? stripW : width.value
    const sign = opts.side === 'left' ? 1 : -1

    document.body.style.cursor = 'col-resize'
    document.body.style.userSelect = 'none'

    function onMove(ev: MouseEvent) {
      const delta = (ev.clientX - startX) * sign
      const next = startW + delta
      if (collapsed.value) {
        // 折叠态:向外拖了几像素就展开,宽度跟手(钳到 minWidth..maxWidth);往里拖不操作
        if (delta > 4) {
          collapsed.value = false
          width.value = Math.max(opts.minWidth, Math.min(opts.maxWidth, next))
        }
      } else {
        // 展开态:允许临时低于 minWidth(给折叠反馈),但不能小于 40px / 大于 maxWidth
        width.value = Math.max(40, Math.min(opts.maxWidth, next))
      }
    }

    function onUp() {
      dragging.value = false
      document.body.style.cursor = ''
      document.body.style.userSelect = ''
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
      cleanup = null

      if (!collapsed.value) {
        if (width.value < opts.collapseAtWidth) {
          collapsed.value = true
          width.value = opts.defaultWidth
        } else if (width.value < opts.minWidth) {
          width.value = opts.minWidth
        }
      }
    }

    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
    cleanup = () => {
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
      document.body.style.cursor = ''
      document.body.style.userSelect = ''
    }
  }

  // 卸载时若还在拖,清理监听器
  onBeforeUnmount(() => {
    if (cleanup) cleanup()
  })

  return { width, collapsed, dragging, startDrag }
}
