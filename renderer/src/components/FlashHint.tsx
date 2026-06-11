import { AbsoluteFill, interpolate, useCurrentFrame, useVideoConfig } from 'remotion'

// 章节断点的轻量级暗示。当 BlackHoldFrame 与字幕 cue 重叠被拒绝时回退到本组件:
// 白色叠层短闪 0 → 18% → 0(三角波,峰值在 35% 时间点),不依赖静音、不遮画面、不挤时间轴。
// 与 BlackHoldFrame 一样靠 Sequence 时长定型(典型 0.3s),由 StoryComposition 控制。
export const FlashHint: React.FC = () => {
  const frame = useCurrentFrame()
  const { durationInFrames } = useVideoConfig()
  const t = durationInFrames > 0 ? frame / durationInFrames : 0

  const peak = 0.35
  const opacity =
    t < peak
      ? interpolate(t, [0, peak], [0, 0.18], { extrapolateRight: 'clamp' })
      : interpolate(t, [peak, 1], [0.18, 0], { extrapolateLeft: 'clamp' })

  return <AbsoluteFill style={{ backgroundColor: '#fff', opacity }} />
}
