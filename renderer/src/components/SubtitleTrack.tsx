import { useCurrentFrame, useVideoConfig } from 'remotion'
import type { SubtitleCue } from '../types'

// 字幕轨:根据当前帧 → 当前秒 → 找出该显示的 cue。
// highlightKeywords 命中的词会金色加粗显示,其他正常白字。
// large=true(大字幕):fontSize 72 / bottom 优先用 bottomRatio prop(>0 时),否则按视频比例智能默认:
//   竖屏(width<height) → height*25.7%(避开抖音底部标题+3.5 字幕高度)
//   横屏(width>=height) → height*12.5%
//   亮黄高亮 #FFD800 / 描边 4px
// large=false(默认):fontSize 56 / bottom 200 / 暗金高亮 #d4af37 / 描边 2px
type Props = {
  cues: SubtitleCue[]
  highlightKeywords?: string[]
  large?: boolean
  /** 字幕距底边比例 0-0.5;0 = 走 large 模式智能默认。从 preset.subtitle_bottom_ratio 透传。 */
  bottomRatio?: number
}

type Token = { text: string; highlight: boolean }

function tokenize(text: string, keywords: string[]): Token[] {
  if (!keywords || keywords.length === 0) return [{ text, highlight: false }]
  // 长关键词优先匹配,避免短词把长词切碎
  const sorted = [...keywords].filter(k => k && k.length > 0).sort((a, b) => b.length - a.length)
  if (sorted.length === 0) return [{ text, highlight: false }]

  const tokens: Token[] = []
  let i = 0
  while (i < text.length) {
    let matched: string | null = null
    for (const kw of sorted) {
      if (text.startsWith(kw, i)) {
        matched = kw
        break
      }
    }
    if (matched) {
      tokens.push({ text: matched, highlight: true })
      i += matched.length
    } else {
      const last = tokens[tokens.length - 1]
      if (last && !last.highlight) {
        last.text += text[i]
      } else {
        tokens.push({ text: text[i], highlight: false })
      }
      i += 1
    }
  }
  return tokens
}

export const SubtitleTrack: React.FC<Props> = ({ cues, highlightKeywords = [], large = false, bottomRatio = 0 }) => {
  const frame = useCurrentFrame()
  const { fps, width, height } = useVideoConfig()
  const sec = frame / fps

  const current = cues.find((c) => sec >= c.startSec && sec < c.endSec)
  if (!current) return null

  const tokens = tokenize(current.text, highlightKeywords)

  const fontSize = large ? 72 : 56
  // 优先用 prop 透传值(来自 preset.subtitle_bottom_ratio),0 则走智能默认
  const isPortrait = width < height
  const effectiveRatio = bottomRatio > 0
    ? bottomRatio
    : (large ? (isPortrait ? 0.257 : 0.125) : null)
  const bottomPx = effectiveRatio !== null ? Math.round(height * effectiveRatio) : 200
  const highlightColor = large ? '#FFD800' : '#d4af37'
  const textShadow = large
    ? '-4px -4px 0 #000, 4px -4px 0 #000, -4px 4px 0 #000, 4px 4px 0 #000, 0 6px 16px rgba(0,0,0,0.9)'
    : '-2px -2px 0 #000, 2px -2px 0 #000, -2px 2px 0 #000, 2px 2px 0 #000, 0 4px 12px rgba(0,0,0,0.85)'

  return (
    <div
      style={{
        position: 'absolute',
        left: 0,
        right: 0,
        bottom: bottomPx,
        display: 'flex',
        justifyContent: 'center',
        padding: '0 64px',
        pointerEvents: 'none',
      }}
    >
      <div
        style={{
          fontSize,
          color: '#fff',
          fontFamily: '"PingFang SC", "Microsoft YaHei", sans-serif',
          fontWeight: 600,
          textAlign: 'center',
          lineHeight: 1.4,
          letterSpacing: 1,
          textShadow,
          maxWidth: '95%',
          whiteSpace: 'pre-wrap',
        }}
      >
        {tokens.map((t, i) =>
          t.highlight ? (
            <span key={i} style={{ color: highlightColor, fontWeight: 800 }}>
              {t.text}
            </span>
          ) : (
            <span key={i}>{t.text}</span>
          )
        )}
      </div>
    </div>
  )
}
