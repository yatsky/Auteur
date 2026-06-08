import { AbsoluteFill, Img, useCurrentFrame, useVideoConfig } from 'remotion'
import type { MotionDir } from '../types'

// 5 种运镜:in 推近 / out 拉远 / panLeft 向左平移 / panRight 向右平移 / static 静止。
// pan 时配 1.05 倍轻微缩放,避免边缘漏出黑边。
// 镜头切换硬切(无 fade)—— 章节呼吸由 StoryComposition 的 BlackHoldFrame 单独负责。
type Props = {
  imageUrl: string
  motion: MotionDir
}

export const MotionImage: React.FC<Props> = ({ imageUrl, motion }) => {
  const frame = useCurrentFrame()
  const { durationInFrames } = useVideoConfig()

  // 0..1 进度
  const t = durationInFrames > 0 ? frame / durationInFrames : 0

  let scale = 1
  let translateX = 0
  const translateY = 0

  switch (motion) {
    case 'in':
      scale = 1.0 + 0.08 * t
      break
    case 'out':
      scale = 1.08 - 0.08 * t
      break
    case 'panLeft':
      scale = 1.05
      translateX = -3 * t
      break
    case 'panRight':
      scale = 1.05
      translateX = 3 * t
      break
    case 'static':
      scale = 1.0
      break
  }

  return (
    <AbsoluteFill style={{ backgroundColor: '#000' }}>
      <AbsoluteFill
        style={{
          transform: `scale(${scale}) translate(${translateX}%, ${translateY}%)`,
        }}
      >
        <Img src={imageUrl} style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
      </AbsoluteFill>
    </AbsoluteFill>
  )
}
