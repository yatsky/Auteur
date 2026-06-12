import { AbsoluteFill, Audio, Img, Sequence } from 'remotion'
import { BlackHoldFrame } from './components/BlackHoldFrame'
import { FlashHint } from './components/FlashHint'
import { HookTitle } from './components/HookTitle'
import { MotionImage } from './components/MotionImage'
import { SubtitleTrack } from './components/SubtitleTrack'
import { Watermark } from './components/Watermark'
import { FPS, type StoryProps, type MotionDir, type SubtitleCue } from './types'

const sec = (s: number) => Math.round(s * FPS)

// 通用 Story 视频合成器(横屏/竖屏 / LifeCopy alias 共用):
//  - 章节断点黑帧:在 sectionCode 切换处插 0.3s 黑帧(前后各 0.10s 安全缓冲不与 cue 重叠)。
//  - 开头 hook 段 0..hookFrames:多张快切 + 钩子旁白 + 中央大字标题 + 翻书音效。
//    主体 shots / audio / 字幕整体后推 hookFrames(用 Sequence from=hookFrames 包裹)。
//    hookDurationSec=0 时退回原行为,无 hook。
const CHAPTER_BREAK_SEC = 0.3
const CHAPTER_SAFE_BEFORE = 0.10
const CHAPTER_SAFE_AFTER = 0.10
const SUBTITLE_MAX_CHARS = 18
const SUBTITLE_SPLIT_REGEX = /[。?!;:,、]/

function splitLongCues(cues: SubtitleCue[]): SubtitleCue[] {
  const out: SubtitleCue[] = []
  for (const c of cues) {
    if (c.text.length <= SUBTITLE_MAX_CHARS) {
      out.push(c)
      continue
    }
    const rawParts: string[] = []
    let buf = ''
    for (let i = 0; i < c.text.length; i++) {
      buf += c.text[i]
      if (SUBTITLE_SPLIT_REGEX.test(c.text[i])) {
        rawParts.push(buf)
        buf = ''
      }
    }
    if (buf) rawParts.push(buf)

    const merged: string[] = []
    for (const p of rawParts) {
      if (merged.length > 0 && merged[merged.length - 1].length + p.length <= SUBTITLE_MAX_CHARS) {
        merged[merged.length - 1] += p
      } else {
        merged.push(p)
      }
    }

    const finalParts: string[] = []
    for (const p of merged) {
      if (p.length <= SUBTITLE_MAX_CHARS) {
        finalParts.push(p)
      } else {
        for (let i = 0; i < p.length; i += SUBTITLE_MAX_CHARS) {
          finalParts.push(p.substring(i, Math.min(i + SUBTITLE_MAX_CHARS, p.length)))
        }
      }
    }

    const totalChars = finalParts.reduce((s, p) => s + p.length, 0)
    const totalDur = c.endSec - c.startSec
    let t = c.startSec
    for (let i = 0; i < finalParts.length; i++) {
      const p = finalParts[i]
      const isLast = i === finalParts.length - 1
      const d = isLast ? c.endSec - t : totalDur * (p.length / totalChars)
      out.push({ startSec: t, endSec: t + d, text: p })
      t += d
    }
  }
  return out
}

export const StoryComposition: React.FC<StoryProps> = ({
  audioUrl,
  shots,
  subtitleCues,
  watermark,
  plan,
  hookImages,
  hookAudioUrl,
  hookText,
  hookDurationSec,
  hookPageFlipSoundUrl,
}) => {
  const motionByShot = new Map<number, MotionDir>(
    plan.shotPlans.map((p) => [p.shotId, p.motion]),
  )

  const cuesShort = splitLongCues(subtitleCues).map((c) => ({
    ...c,
    text: c.text.replace(/[,。?!;、,.?!;]/g, '').trim(),
  }))

  // 章节断点检测(用拆短后的 cuesShort 做 overlap)。
  // black 模式需要 cue 间隙(画面变黑但音频不停,与字幕重叠会出现"黑屏但还在说话")。
  // overlap 时回退到 flash:白色叠层短闪不依赖静音,补回章节感不与字幕冲突。
  const chapterBreaks: { startSec: number; kind: 'black' | 'flash' }[] = []
  for (let i = 1; i < shots.length; i++) {
    const prev = shots[i - 1].sectionCode
    const curr = shots[i].sectionCode
    if (!prev || !curr || prev === curr) continue
    const breakStart = shots[i].startSec - CHAPTER_BREAK_SEC / 2
    const breakEnd = breakStart + CHAPTER_BREAK_SEC
    if (breakStart < 0) continue
    const safeStart = breakStart - CHAPTER_SAFE_BEFORE
    const safeEnd = breakEnd + CHAPTER_SAFE_AFTER
    const overlapsCue = cuesShort.some(
      (c) => safeEnd > c.startSec && safeStart < c.endSec,
    )
    chapterBreaks.push({ startSec: breakStart, kind: overlapsCue ? 'flash' : 'black' })
  }

  const hookEnabled =
    hookDurationSec > 0 && hookImages.length > 0 && hookAudioUrl.length > 0
  const hookFrames = hookEnabled ? sec(hookDurationSec) : 0
  const hookCellFrames = hookEnabled ? Math.floor(hookFrames / hookImages.length) : 0

  return (
    <AbsoluteFill style={{ backgroundColor: '#fff' }}>
      {/* hook 段(0..hookFrames):快切图 + 钩子旁白 + 中央大字标题 + 翻书音效 */}
      {hookEnabled && (
        <Sequence from={0} durationInFrames={hookFrames} name="hook">
          <Audio src={hookAudioUrl} />
          {/* 翻书音效在 hook 段外层 loop,跨整段持续播放,听感"连续翻页"而不是多次"咔咔咔"碎片 */}
          {hookPageFlipSoundUrl && <Audio src={hookPageFlipSoundUrl} loop />}
          {hookImages.map((url, i) => (
            <Sequence
              key={`hook-img-${i}`}
              from={i * hookCellFrames}
              durationInFrames={hookCellFrames}
              name={`hook-cell-${i}`}
            >
              <AbsoluteFill style={{ backgroundColor: '#000' }}>
                <Img
                  src={url}
                  style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                  delayRenderTimeoutInMilliseconds={120000}
                  delayRenderRetries={2}
                />
              </AbsoluteFill>
            </Sequence>
          ))}
          {/* 中央大字标题分两行,前段(冒号前)白色,后段(冒号后)黄色 */}
          {hookText && <HookTitle text={hookText} />}
        </Sequence>
      )}

      {/* 主体段(hookFrames..end):shots / audio / 字幕 / 章节断点 */}
      <Sequence from={hookFrames} name="main">
        {audioUrl && <Audio src={audioUrl} />}

        {shots.map((shot) => (
          <Sequence
            key={shot.shotId}
            from={sec(shot.startSec)}
            durationInFrames={sec(shot.durationSec)}
            name={`shot-${shot.shotId}`}
          >
            <MotionImage
              imageUrl={shot.imageUrl}
              motion={motionByShot.get(shot.shotId) ?? 'in'}
              shotId={shot.shotId}
            />
          </Sequence>
        ))}

        {chapterBreaks.map((b, i) => (
          <Sequence
            key={`chapter-${i}`}
            from={sec(b.startSec)}
            durationInFrames={sec(CHAPTER_BREAK_SEC)}
            name={`chapterBreak-${i}-${b.kind}`}
          >
            {b.kind === 'black' ? <BlackHoldFrame /> : <FlashHint />}
          </Sequence>
        ))}

        <SubtitleTrack cues={cuesShort} highlightKeywords={plan.highlightKeywords} large />
      </Sequence>

      {/* 整片水印(贯穿 hook + 主体)。空串 = 不渲染 */}
      {watermark && <Watermark text={watermark} />}
    </AbsoluteFill>
  )
}
