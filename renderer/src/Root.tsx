import { Composition } from 'remotion'
import { StoryComposition } from './StoryComposition'
import {
  FPS,
  STORY_HORIZONTAL_WIDTH,
  STORY_HORIZONTAL_HEIGHT,
  STORY_VERTICAL_WIDTH,
  STORY_VERTICAL_HEIGHT,
  storyPropsSchema,
  type StoryProps,
} from './types'

const storyHorizontalDefaultProps: StoryProps = {
  audioUrl: '',
  audioDurationSec: 18,
  watermark: '',
  shots: [
    { shotId: 1, imageUrl: 'https://picsum.photos/seed/story-h-1/1920/1080', startSec: 0,  durationSec: 6, sectionCode: '①' },
    { shotId: 2, imageUrl: 'https://picsum.photos/seed/story-h-2/1920/1080', startSec: 6,  durationSec: 6, sectionCode: '①' },
    { shotId: 3, imageUrl: 'https://picsum.photos/seed/story-h-3/1920/1080', startSec: 12, durationSec: 6, sectionCode: '②' },
  ],
  subtitleCues: [
    { startSec: 0,  endSec: 5,  text: '这是一段示例字幕,展示横屏 Story composition。' },
    { startSec: 6,  endSec: 11, text: '后端会按 SRT 自动填充,这里只是开发预览占位。' },
    { startSec: 12, endSec: 17, text: '用户可在前端「预设库」配置自己的 prompt。' },
  ],
  plan: { shotPlans: [], highlightKeywords: [], blackHoldsAt: [] },
  hookImages: [],
  hookAudioUrl: '',
  hookText: '',
  hookDurationSec: 0,
  hookPageFlipSoundUrl: '',
}

const storyVerticalDefaultProps: StoryProps = {
  ...storyHorizontalDefaultProps,
  shots: storyHorizontalDefaultProps.shots.map(s => ({
    ...s,
    imageUrl: s.imageUrl.replace('/1920/1080', '/1080/1920'),
  })),
}

export const RemotionRoot: React.FC = () => {
  return (
    <>
      {/* LifeCopy:保留作 alias,兼容历史 preset.composition_id="LifeCopy"。 */}
      <Composition
        id="LifeCopy"
        component={StoryComposition}
        fps={FPS}
        width={STORY_HORIZONTAL_WIDTH}
        height={STORY_HORIZONTAL_HEIGHT}
        defaultProps={storyHorizontalDefaultProps}
        schema={storyPropsSchema}
        calculateMetadata={({ props }) => ({
          // hookDurationSec=0 时退回原行为(无 hook)
          durationInFrames: Math.ceil((props.hookDurationSec + props.audioDurationSec) * FPS),
          fps: FPS,
          width: STORY_HORIZONTAL_WIDTH,
          height: STORY_HORIZONTAL_HEIGHT,
        })}
      />

      <Composition
        id="StoryHorizontal"
        component={StoryComposition}
        fps={FPS}
        width={STORY_HORIZONTAL_WIDTH}
        height={STORY_HORIZONTAL_HEIGHT}
        defaultProps={storyHorizontalDefaultProps}
        schema={storyPropsSchema}
        calculateMetadata={({ props }) => ({
          durationInFrames: Math.ceil((props.hookDurationSec + props.audioDurationSec) * FPS),
          fps: FPS,
          width: STORY_HORIZONTAL_WIDTH,
          height: STORY_HORIZONTAL_HEIGHT,
        })}
      />

      <Composition
        id="StoryVertical"
        component={StoryComposition}
        fps={FPS}
        width={STORY_VERTICAL_WIDTH}
        height={STORY_VERTICAL_HEIGHT}
        defaultProps={storyVerticalDefaultProps}
        schema={storyPropsSchema}
        calculateMetadata={({ props }) => ({
          durationInFrames: Math.ceil((props.hookDurationSec + props.audioDurationSec) * FPS),
          fps: FPS,
          width: STORY_VERTICAL_WIDTH,
          height: STORY_VERTICAL_HEIGHT,
        })}
      />
    </>
  )
}
