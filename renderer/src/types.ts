import { z } from 'zod'

// 单个分镜:对应 auteur 后端 ShotTimingResolver 输出。
// sectionCode 用来检测章节断点(在边界插黑帧)。
export const shotSchema = z.object({
  shotId: z.number(),
  imageUrl: z.string(),
  startSec: z.number(),
  durationSec: z.number(),
  sectionCode: z.string().default(''),
})

export type Shot = z.infer<typeof shotSchema>

// 字幕 cue:由 backend 解析 SRT 后传过来
export const subtitleCueSchema = z.object({
  startSec: z.number(),
  endSec: z.number(),
  text: z.string(),
})

export type SubtitleCue = z.infer<typeof subtitleCueSchema>

export const motionDirSchema = z.enum(['in', 'out', 'panLeft', 'panRight', 'static'])
export type MotionDir = z.infer<typeof motionDirSchema>

export const shotPlanSchema = z.object({
  shotId: z.number(),
  motion: motionDirSchema.default('in'),
})

export const renderPlanSchema = z.object({
  // 每镜的运镜方向。没列出的 shotId 默认 in
  shotPlans: z.array(shotPlanSchema).default([]),
  // 字幕里命中的词会金色加粗
  highlightKeywords: z.array(z.string()).default([]),
  // 黑屏静音反差锚点(秒,每个持续 0.5s,前后各 0.1s 渐变)。空 = 无反差锚点
  blackHoldsAt: z.array(z.number()).default([]),
})

export type RenderPlan = z.infer<typeof renderPlanSchema>

export const FPS = 30

// Story:横屏 1920×1080(知识/纪实)/ 竖屏 1080×1920(短视频)
export const STORY_HORIZONTAL_WIDTH = 1920
export const STORY_HORIZONTAL_HEIGHT = 1080
export const STORY_VERTICAL_WIDTH = 1080
export const STORY_VERTICAL_HEIGHT = 1920

// Story 渲染 props 统一 schema(横屏/竖屏 / LifeCopy alias 共用同一个组件、同一份字段)。
// watermark 默认空,用户在 preset.watermark_text 自行配置;hookDurationSec=0 退回无 hook。
export const storyPropsSchema = z.object({
  audioUrl: z.string(),
  audioDurationSec: z.number(),
  shots: z.array(shotSchema),
  subtitleCues: z.array(subtitleCueSchema).default([]),
  watermark: z.string().default(''),
  plan: renderPlanSchema.default({
    shotPlans: [],
    highlightKeywords: [],
    blackHoldsAt: [],
  }),
  hookImages: z.array(z.string()).default([]),
  hookAudioUrl: z.string().default(''),
  hookText: z.string().default(''),
  hookDurationSec: z.number().default(0),
  hookPageFlipSoundUrl: z.string().default(''),
  chapterBreakSec: z.number().default(0.3),
})
export type StoryProps = z.infer<typeof storyPropsSchema>
