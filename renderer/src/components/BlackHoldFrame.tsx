import { AbsoluteFill, interpolate, useCurrentFrame, useVideoConfig } from 'remotion'

// 黑屏静音反差锚点。完整时长由 Sequence 控制(典型 0.5s):前 0.1s 淡入,中段维持,后 0.1s 淡出。
//
// 注意:这个组件只覆盖画面,不影响 <Audio>。黑屏期间旁白会继续播,可能造成"画面黑了但还在说话"。
//   导演 LLM 必须保证 blackHoldsAt 落在 SRT 字幕的间隙;backend RenderPlanService 拿到 LLM 输出
//   后会强制 validate,踢掉与 cue 重叠的点。
export const BlackHoldFrame: React.FC = () => {
  const frame = useCurrentFrame()
  const { fps, durationInFrames } = useVideoConfig()

  const fadeInEnd = fps * 0.1
  const fadeOutStart = durationInFrames - fps * 0.1

  let opacity = 1
  if (frame < fadeInEnd) {
    opacity = interpolate(frame, [0, fadeInEnd], [0, 1], { extrapolateRight: 'clamp' })
  } else if (frame > fadeOutStart) {
    opacity = interpolate(frame, [fadeOutStart, durationInFrames], [1, 0], { extrapolateLeft: 'clamp' })
  }

  return <AbsoluteFill style={{ backgroundColor: '#000', opacity }} />
}
