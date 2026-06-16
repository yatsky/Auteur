type Props = {
  text: string
}

export const Watermark: React.FC<Props> = ({ text }) => {
  return (
    <div
      style={{
        position: 'absolute',
        top: 24,
        left: 28,
        color: 'rgba(255, 255, 255, 0.85)',
        fontSize: 22,
        fontFamily: '"PingFang SC", "Microsoft YaHei", sans-serif',
        fontWeight: 500,
        letterSpacing: 1,
        textShadow:
          '-1px -1px 0 rgba(0,0,0,0.7), 1px -1px 0 rgba(0,0,0,0.7), -1px 1px 0 rgba(0,0,0,0.7), 1px 1px 0 rgba(0,0,0,0.7)',
        pointerEvents: 'none',
      }}
    >
      {text}
    </div>
  )
}
