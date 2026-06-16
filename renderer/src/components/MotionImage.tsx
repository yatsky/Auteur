import { AbsoluteFill, Img, useCurrentFrame, useVideoConfig } from 'remotion'
import type { MotionDir } from '../types'

// 5 种运镜:in 推近 / out 拉远 / panLeft 向左平移 / panRight 向右平移 / static 静止。
// pan 时配 1.05 倍轻微缩放,避免边缘漏出黑边。
// 镜头切换硬切(无 fade)—— 章节呼吸由 StoryComposition 的 BlackHoldFrame 单独负责。
//
// 幅度抖动:zoom 0.06~0.10、pan 2.5%~3.5% 由 shotId 派生,同 shotId 抖动稳定可复现,
// 不同 shotId 互不相同。配合 backend 的相邻去重选 motion,合成产物在画面节奏上不再呈现固定模式。
type Props = {
  imageUrl: string
  motion: MotionDir
  shotId: number
}

function jitter01(shotId: number, salt: number): number {
  const h = ((shotId * 2654435761) ^ (salt * 40503)) >>> 0
  return h / 0x100000000
}

export const MotionImage: React.FC<Props> = ({ imageUrl, motion, shotId }) => {
  const frame = useCurrentFrame()
  const { durationInFrames } = useVideoConfig()

  const t = durationInFrames > 0 ? frame / durationInFrames : 0

  const zoomDelta = 0.06 + 0.04 * jitter01(shotId, 1)
  const panDist = 2.5 + 1.0 * jitter01(shotId, 2)

  let scale = 1
  let translateX = 0
  const translateY = 0

  switch (motion) {
    case 'in':
      scale = 1.0 + zoomDelta * t
      break
    case 'out':
      scale = 1.0 + zoomDelta - zoomDelta * t
      break
    case 'panLeft':
      scale = 1.05
      translateX = -panDist * t
      break
    case 'panRight':
      scale = 1.05
      translateX = panDist * t
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
        <Img
          src={imageUrl}
          style={{ width: '100%', height: '100%', objectFit: 'cover' }}
          delayRenderTimeoutInMilliseconds={120000}
          delayRenderRetries={2}
        />
      </AbsoluteFill>
    </AbsoluteFill>
  )
}
