// 开头 hook 段中央大字标题。整个 hook 持续显示,屏幕正中央。
// 标题按冒号(中英)分两行:前段白色,后段黄色(高亮焦点)。无冒号时整段当后段处理。

type Props = {
  text: string
}

const COMMON_TEXT_STYLE: React.CSSProperties = {
  fontSize: 100,
  fontWeight: 900,
  fontFamily: '"PingFang SC", "Microsoft YaHei", sans-serif',
  textAlign: 'center',
  lineHeight: 1.3,
  letterSpacing: 4,
  whiteSpace: 'pre-wrap',
  textShadow:
    '-6px -6px 0 #000, 6px -6px 0 #000, -6px 6px 0 #000, 6px 6px 0 #000, ' +
    '0 0 12px rgba(0,0,0,0.6), 0 10px 24px rgba(0,0,0,0.9)',
}

export const HookTitle: React.FC<Props> = ({ text }) => {
  if (!text) return null

  const idx = text.search(/[::]/)
  const firstLine = idx >= 0 ? text.substring(0, idx).trim() : ''
  const secondLine = idx >= 0 ? text.substring(idx + 1).trim() : text.trim()

  return (
    <div
      style={{
        position: 'absolute',
        top: 0,
        left: 0,
        right: 0,
        bottom: 0,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        padding: '0 80px',
        pointerEvents: 'none',
        gap: 16,
      }}
    >
      {firstLine && (
        <div style={{ ...COMMON_TEXT_STYLE, color: '#fff' }}>
          {firstLine}
        </div>
      )}
      {secondLine && (
        <div style={{ ...COMMON_TEXT_STYLE, color: '#FFD800' }}>
          {secondLine}
        </div>
      )}
    </div>
  )
}

