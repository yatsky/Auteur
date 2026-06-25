// 每个模板都接收 (ctx, w, h, brand, design, heroImg),内部用 w/h 比例自适应 3:4 / 4:3 / 16:9。
// 颜色全部从 brand 取,改主色三比例同步变。
import type { BrandIdentity, CoverDesign } from '../types'

export type CoverRenderFn = (
  ctx: CanvasRenderingContext2D,
  w: number,
  h: number,
  brand: BrandIdentity,
  design: CoverDesign,
  heroImg: HTMLImageElement | null,
  logoImg: HTMLImageElement | null,
) => void

export interface CoverTemplateMeta {
  id: string
  name: string
  description: string
  render: CoverRenderFn
}

// canvas measureText 在中文上每个字宽差不多,按字宽贪心断行。
function wrapText(
  ctx: CanvasRenderingContext2D,
  text: string,
  maxWidth: number,
): string[] {
  if (!text) return []
  const lines: string[] = []
  let cur = ''
  for (const ch of Array.from(text)) {
    if (ch === '\n') {
      lines.push(cur)
      cur = ''
      continue
    }
    const next = cur + ch
    if (ctx.measureText(next).width > maxWidth && cur) {
      lines.push(cur)
      cur = ch
    } else {
      cur = next
    }
  }
  if (cur) lines.push(cur)
  return lines
}

// 像 CSS background-size: cover,填满目标区域,中心裁切,不变形。
function drawCover(
  ctx: CanvasRenderingContext2D,
  img: HTMLImageElement,
  x: number, y: number, w: number, h: number,
) {
  const imgRatio = img.naturalWidth / img.naturalHeight
  const boxRatio = w / h
  let sx = 0, sy = 0, sw = img.naturalWidth, sh = img.naturalHeight
  if (imgRatio > boxRatio) {
    sw = img.naturalHeight * boxRatio
    sx = (img.naturalWidth - sw) / 2
  } else {
    sh = img.naturalWidth / boxRatio
    sy = (img.naturalHeight - sh) / 2
  }
  ctx.drawImage(img, sx, sy, sw, sh, x, y, w, h)
}

function drawLogoAndAuthor(
  ctx: CanvasRenderingContext2D,
  x: number, y: number, h: number,
  brand: BrandIdentity,
  logoImg: HTMLImageElement | null,
  textColor: string,
) {
  let cursorX = x
  if (logoImg) {
    const size = h
    const cx = cursorX + size / 2
    const cy = y + size / 2
    const r = size / 2

    ctx.save()
    ctx.beginPath()
    ctx.arc(cx, cy, r, 0, Math.PI * 2)
    ctx.closePath()
    ctx.clip()
    ctx.fillStyle = brand.bgColor
    ctx.fillRect(cursorX, y, size, size)
    const scale = Math.max(size / logoImg.naturalWidth, size / logoImg.naturalHeight)
    const dw = logoImg.naturalWidth * scale
    const dh = logoImg.naturalHeight * scale
    ctx.drawImage(logoImg, cursorX + (size - dw) / 2, y + (size - dh) / 2, dw, dh)
    ctx.restore()

    ctx.strokeStyle = brand.secondaryColor
    ctx.lineWidth = Math.max(1.5, h * 0.04)
    ctx.beginPath()
    ctx.arc(cx, cy, r - ctx.lineWidth / 2, 0, Math.PI * 2)
    ctx.stroke()

    cursorX += size + h * 0.4
  }

  const primaryText = brand.brandName || ''
  const secondaryText = brand.authorName || ''
  if (!primaryText && !secondaryText) return
  ctx.textAlign = 'left'
  ctx.textBaseline = 'middle'
  ctx.fillStyle = textColor
  if (primaryText) {
    ctx.font = `${Math.round(h * 0.55)}px ${brand.titleFont}`
    ctx.fillText(primaryText, cursorX, y + h / 2)
    cursorX += ctx.measureText(primaryText).width
  }
  if (secondaryText) {
    const prefix = primaryText ? '  @' : '@'
    ctx.globalAlpha = 0.7
    ctx.font = `${Math.round(h * 0.42)}px ${brand.titleFont}`
    ctx.fillText(prefix + secondaryText, cursorX, y + h / 2)
    ctx.globalAlpha = 1
  }
}

/**
 * 解析 `**word**` 标记 → 高亮 segments。
 * "存款 **100 万**,降息后少 **2500 块**" → [
 *   {text: "存款 ", highlight: false},
 *   {text: "100 万", highlight: true},
 *   {text: ",降息后少 ", highlight: false},
 *   {text: "2500 块", highlight: true},
 * ]
 */
function parseHighlights(text: string): Array<{ text: string; highlight: boolean }> {
  const out: Array<{ text: string; highlight: boolean }> = []
  const regex = /\*\*([^*]+?)\*\*/g
  let cursor = 0
  let m: RegExpExecArray | null
  while ((m = regex.exec(text)) !== null) {
    if (m.index > cursor) out.push({ text: text.slice(cursor, m.index), highlight: false })
    out.push({ text: m[1], highlight: true })
    cursor = m.index + m[0].length
  }
  if (cursor < text.length) out.push({ text: text.slice(cursor), highlight: false })
  if (out.length === 0) out.push({ text: '', highlight: false })
  return out
}

/**
 * 把"已断好行的字符串"按全局字符索引切回 mini-segments (该行内同色连续段),
 * 用于渲染时一段一段 fillText(每段不同颜色)。
 */
function lineToColoredSegments(
  line: string,
  startCharIdx: number,
  highlightMask: boolean[],
): Array<{ text: string; highlight: boolean }> {
  const segs: Array<{ text: string; highlight: boolean }> = []
  let cur = ''
  let curH = highlightMask[startCharIdx] ?? false
  for (let i = 0; i < line.length; i++) {
    const h = highlightMask[startCharIdx + i] ?? false
    if (h === curH) {
      cur += line[i]
    } else {
      if (cur) segs.push({ text: cur, highlight: curH })
      cur = line[i]
      curH = h
    }
  }
  if (cur) segs.push({ text: cur, highlight: curH })
  return segs
}

// 模板 1:lifecopy-classic —— 录取通知书风格,红底圆角 hero(保留 hero 原色) + 底部主副标题
// 标题用 "\n" 分隔主副:第一行小字提示语,第二行大字主标题。无换行时整段当大字主标题。
const renderLifecopyClassic: CoverRenderFn = (ctx, w, h, brand, design, heroImg, _logoImg) => {
  ctx.fillStyle = '#1a1a1a'
  ctx.fillRect(0, 0, w, h)

  // 红色圆角 hero 框,占上 70% 高度
  const padX = w * 0.04
  const heroY = h * 0.04
  const heroW = w - padX * 2
  const heroH = h * 0.66
  const radius = Math.min(heroW, heroH) * 0.04

  ctx.save()
  roundedRectPath(ctx, padX, heroY, heroW, heroH, radius)
  ctx.fillStyle = '#8B1A1A'
  ctx.fill()
  ctx.clip()
  if (heroImg) drawCover(ctx, heroImg, padX, heroY, heroW, heroH)
  ctx.restore()

  const titleAreaY = heroY + heroH + h * 0.04
  const titleAreaH = h - titleAreaY - h * 0.04

  const rawTitle = design.titleText || '今天体验的人生:示例'
  const lines = rawTitle.split('\n').map((s) => s.trim()).filter(Boolean)
  const main = lines.length >= 2 ? lines[0] : null
  const sub = lines.length >= 2 ? lines.slice(1).join(' ') : lines[0] || ''

  const titleMaxW = w * 0.92
  const ratio = w / h
  const subSize = ratio < 1 ? Math.round(w * 0.105) : Math.round(h * 0.13)
  const mainSize = Math.round(subSize * 0.6)

  ctx.textAlign = 'center'
  ctx.textBaseline = 'alphabetic'

  const lineGap = subSize * 0.18
  let totalH = subSize
  if (main) totalH += mainSize + lineGap

  let yCursor = titleAreaY + (titleAreaH - totalH) / 2

  if (main) {
    yCursor += mainSize
    ctx.font = `bold ${mainSize}px ${brand.titleFont}`
    drawWhitePlain(ctx, main, w / 2, yCursor, mainSize, titleMaxW)
    yCursor += lineGap
  }

  yCursor += subSize - (main ? 0 : 0)
  ctx.font = `900 ${subSize}px ${brand.titleFont}`
  drawStrokedYellow(ctx, sub, w / 2, yCursor, subSize, titleMaxW)
}

// 模板 2:centered-highlight —— 主图填满 + 中央半透明深色横带 + 居中标题(**词**黄底黑描边)
// titleText 里用 `**word**` 包裹的部分黄字黑描边,其余白色。横带仅覆盖标题区域,主图大部分清晰可见。
const renderCenteredHighlight: CoverRenderFn = (ctx, w, h, brand, design, heroImg, logoImg) => {
  // 背景:hero 图填满;无 hero 时纯 bgColor 兜底
  if (heroImg) {
    drawCover(ctx, heroImg, 0, 0, w, h)
  } else {
    ctx.fillStyle = brand.bgColor
    ctx.fillRect(0, 0, w, h)
  }

  // 顶 / 底装饰条(品牌主色)
  const decoH = Math.max(4, h * 0.006)
  ctx.fillStyle = brand.primaryColor
  ctx.fillRect(0, 0, w, decoH)
  ctx.fillRect(0, h - decoH, w, decoH)

  // 标题排版:先 parse + wrap,知道实际有几行,再决定中央带位置
  const padX = w * 0.08
  const maxTextW = w - padX * 2
  const ratio = w / h
  const titleSize = ratio < 1 ? Math.round(w * 0.105) : Math.round(h * 0.135)
  ctx.font = `900 ${titleSize}px ${brand.titleFont}`

  const segments = parseHighlights(design.titleText || '在标题里用 **双星号** 包裹重点词')
  const plain = segments.map((s) => s.text).join('')
  const highlightMask: boolean[] = []
  segments.forEach((s) => {
    for (let i = 0; i < s.text.length; i++) highlightMask.push(s.highlight)
  })

  const lines = wrapText(ctx, plain, maxTextW).slice(0, 4)
  if (lines.length === 4 && plain.length > lines.join('').length) {
    lines[3] = lines[3].slice(0, -1) + '…'
  }

  const lineGap = titleSize * 0.22
  const blockH = lines.length * (titleSize + lineGap) - lineGap
  // 整体居中,但稍偏上 4% 给底部 logo 让空间
  const blockTopY = (h - blockH) / 2 - h * 0.04
  const startY = blockTopY + titleSize // 第一行 baseline

  // 中央半透明深色横带,覆盖标题区域上下各 padding。仅 hero 模式时画(纯色 bg 已对比够)。
  if (heroImg) {
    const bandPad = h * 0.045
    const bandY = blockTopY - bandPad
    const bandH = blockH + bandPad * 2
    ctx.fillStyle = 'rgba(0, 0, 0, 0.55)'
    ctx.fillRect(0, bandY, w, bandH)
  }

  // 渲染文字
  ctx.textBaseline = 'alphabetic'
  ctx.textAlign = 'left'
  let charCursor = 0
  for (let lineIdx = 0; lineIdx < lines.length; lineIdx++) {
    const line = lines[lineIdx]
    const colored = lineToColoredSegments(line, charCursor, highlightMask)
    charCursor += line.length

    const totalLineW = colored.reduce((sum, s) => sum + ctx.measureText(s.text).width, 0)
    let x = (w - totalLineW) / 2
    const y = startY + lineIdx * (titleSize + lineGap)

    for (const seg of colored) {
      if (seg.highlight) {
        const strokeWidth = Math.max(4, titleSize * 0.08)
        ctx.strokeStyle = '#000000'
        ctx.lineWidth = strokeWidth
        ctx.lineJoin = 'round'
        ctx.miterLimit = 2
        ctx.strokeText(seg.text, x, y)
        ctx.fillStyle = '#FFD93D'
        ctx.fillText(seg.text, x, y)
      } else {
        // 有 hero 时白字直接 fill(横带已托底);无 hero 时主色 fill
        ctx.fillStyle = heroImg ? '#FFFFFF' : brand.primaryColor
        ctx.fillText(seg.text, x, y)
      }
      x += ctx.measureText(seg.text).width
    }
  }

  // 底部 logo + 频道名
  const cornerH = Math.round(h * 0.05)
  drawLogoAndAuthor(
    ctx, w * 0.06, h - cornerH - h * 0.05, cornerH, brand, logoImg,
    heroImg ? '#FFFFFF' : brand.primaryColor,
  )
}

// 黄色填充 + 黑色粗描边(WordArt 风,平台压缩仍醒目) —— lifecopy 用
function drawStrokedYellow(
  ctx: CanvasRenderingContext2D,
  text: string,
  cx: number, y: number,
  fontSize: number,
  maxW: number,
) {
  if (!text) return
  let line = text
  while (ctx.measureText(line).width > maxW && line.length > 1) {
    line = line.slice(0, -1)
  }
  if (line !== text) line = line.slice(0, -1) + '…'

  const strokeWidth = Math.max(4, fontSize * 0.08)
  ctx.strokeStyle = '#000000'
  ctx.lineWidth = strokeWidth
  ctx.lineJoin = 'round'
  ctx.miterLimit = 2
  ctx.strokeText(line, cx, y)
  ctx.fillStyle = '#FFD93D'
  ctx.fillText(line, cx, y)
}

// 纯白填充 —— lifecopy 主标题用
function drawWhitePlain(
  ctx: CanvasRenderingContext2D,
  text: string,
  cx: number, y: number,
  _fontSize: number,
  maxW: number,
) {
  if (!text) return
  let line = text
  while (ctx.measureText(line).width > maxW && line.length > 1) {
    line = line.slice(0, -1)
  }
  if (line !== text) line = line.slice(0, -1) + '…'
  ctx.fillStyle = '#FFFFFF'
  ctx.fillText(line, cx, y)
}


function roundedRectPath(
  ctx: CanvasRenderingContext2D,
  x: number, y: number, w: number, h: number, r: number,
) {
  ctx.beginPath()
  ctx.moveTo(x + r, y)
  ctx.lineTo(x + w - r, y)
  ctx.arcTo(x + w, y, x + w, y + r, r)
  ctx.lineTo(x + w, y + h - r)
  ctx.arcTo(x + w, y + h, x + w - r, y + h, r)
  ctx.lineTo(x + r, y + h)
  ctx.arcTo(x, y + h, x, y + h - r, r)
  ctx.lineTo(x, y + r)
  ctx.arcTo(x, y, x + r, y, r)
  ctx.closePath()
}

export const COVER_TEMPLATES: CoverTemplateMeta[] = [
  {
    id: 'lifecopy-classic',
    name: '上图下标题',
    description: '红色圆角 hero + 底部黄底黑描边大字加粗标题。"今天体验的人生"系列爆款打法,适合 lifecopy 漫画风。',
    render: renderLifecopyClassic,
  },
  {
    id: 'centered-highlight',
    name: '标题居中高亮',
    description: '标题居中,用 **双星号** 包裹的重点词黄底黑描边强调,其余主色。文字优先,适合财经/数字反差/金句类。',
    render: renderCenteredHighlight,
  },
]

export function getTemplate(id: string): CoverTemplateMeta {
  return COVER_TEMPLATES.find((t) => t.id === id) ?? COVER_TEMPLATES[0]
}
