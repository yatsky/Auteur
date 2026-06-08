/**
 * 把 4 个平台原始接口响应 JSON 转成 PublishedVideoUpsert[]。
 * 每个平台一个 normalizer,识别不出返回 [],让 background 无脑 concat。
 *
 * 4 个 normalizer 形态高度同构(提数组 → 逐项转换 → push),用 createNormalizer 工厂生成,
 * 平台特异逻辑只剩"怎么取行 / 怎么取必填三件套 / 怎么映射选填字段"三块。
 */
import type { Platform, PublishedVideoUpsert } from './types'

export interface RawCapture {
  platform: Platform
  origin: string
  raw: unknown
}

function toNum(v: unknown): number | null {
  if (v === null || v === undefined) return null
  if (typeof v === 'number') return Number.isFinite(v) ? v : null
  if (typeof v === 'string') {
    const t = v.trim()
    if (!t) return null
    const n = Number(t)
    return Number.isFinite(n) ? n : null
  }
  return null
}

function toStr(v: unknown): string | null {
  if (v === null || v === undefined) return null
  if (typeof v === 'string') return v
  if (typeof v === 'number' && Math.abs(v) > Number.MAX_SAFE_INTEGER) {
    // 19 位 aweme_id 这种,这里能拿到 number 说明 page-hook 的 BIGINT_KEY_RE 漏配了字段名,
    // 实际值已被 IEEE 754 舍入,toStr 之后无法还原 —— 留个诊断点。
    console.warn('[auteur] toStr 收到不安全大整数 number,精度已丢失,需要在 page-hook _install.ts 的 BIGINT_KEY_RE 白名单加该字段名:', v)
  }
  return String(v)
}

/**
 * 0~1 浮点比率 → 0~100 百分数,保留两位小数。
 * 容错:已是 1~100 的百分数(老接口偶发)原样返回。
 */
function ratioToPct(v: unknown): number | null {
  const n = toNum(v)
  if (n === null) return null
  const pct = n <= 1 ? n * 100 : n
  return Math.round(pct * 100) / 100
}

/** epoch 秒/毫秒 / ISO 字符串 → 后端期望的 LocalDateTime(无时区,Asia/Shanghai 视角)。 */
function toLocalDateTime(v: unknown): string | null {
  if (v === null || v === undefined) return null
  let d: Date | null = null
  if (typeof v === 'number') {
    // 秒 vs 毫秒:长度 < 13 当秒
    d = new Date(v < 1e12 ? v * 1000 : v)
  } else if (typeof v === 'string') {
    const t = v.trim()
    if (!t) return null
    if (/^\d+$/.test(t)) return toLocalDateTime(Number(t))
    const parsed = new Date(t)
    if (!Number.isNaN(parsed.getTime())) d = parsed
  }
  if (!d || Number.isNaN(d.getTime())) return null
  // 后端 jdbc.time_zone = Asia/Shanghai,无时区字符串会被解释成 +08:00
  const shifted = new Date(d.getTime() - d.getTimezoneOffset() * 60_000)
  return shifted.toISOString().slice(0, 19)
}

type Obj = Record<string, unknown>

interface NormalizerConfig {
  platform: Platform
  /** 从 raw payload 顶层对象里取出待循环的行数组。识别不出返回 []。 */
  pickRows: (root: Obj) => unknown[]
  /** 必填三件套:任一返回 null,该行被丢弃。 */
  required: {
    platformVideoId: (item: Obj) => string | null
    title: (item: Obj) => string | null
    publishedAt: (item: Obj) => string | null
  }
  /** 其余可选字段。已确认必填字段非空后调用。返回的对象会被合并到结果行。 */
  optional: (item: Obj) => Partial<PublishedVideoUpsert>
}

/**
 * 工厂:把"提行 → 必填校验 → 选填映射 → push"的循环骨架抽出来,
 * 平台 normalizer 只声明 4 块差异(平台名/取行/必填取法/选填映射)。
 */
function createNormalizer(cfg: NormalizerConfig): (raw: unknown) => PublishedVideoUpsert[] {
  return (raw: unknown): PublishedVideoUpsert[] => {
    if (!raw || typeof raw !== 'object') return []
    const rows = cfg.pickRows(raw as Obj)
    const out: PublishedVideoUpsert[] = []
    for (const it of rows) {
      if (!it || typeof it !== 'object') continue
      const item = it as Obj
      const platformVideoId = cfg.required.platformVideoId(item)
      const title = cfg.required.title(item)
      const publishedAt = cfg.required.publishedAt(item)
      if (!platformVideoId || !title || !publishedAt) continue
      out.push({
        platform: cfg.platform,
        platformVideoId,
        title,
        publishedAt,
        ...cfg.optional(item),
      })
    }
    return out
  }
}

const normalizeDouyin = createNormalizer({
  platform: '抖音',
  pickRows: (root) => (Array.isArray(root.items) ? (root.items as unknown[]) : []),
  required: {
    platformVideoId: (item) => toStr(item.id),
    title: (item) => toStr(item.description),
    publishedAt: (item) => toLocalDateTime(item.create_time),
  },
  optional: (item) => {
    const stat = (item.metrics ?? {}) as Obj
    const videoInfo = (item.video_info ?? {}) as Obj
    // duration 单位毫秒(样本:152673 → 152.67s);> 24h(86400s) 当毫秒,÷1000 取整成秒。
    const rawDur = toNum(videoInfo.duration)
    const durationSeconds =
      rawDur === null ? null : rawDur > 86_400 ? Math.round(rawDur / 1000) : Math.round(rawDur)
    // 封面 url_list 优先取 jpeg(其它格式可能踩签名/防盗链),没有就第一个
    const cover = (item.cover ?? {}) as Obj
    const coverList = Array.isArray(cover.url_list) ? (cover.url_list as unknown[]) : []
    let coverUrl: string | null = null
    for (const u of coverList) {
      const s = toStr(u)
      if (!s) continue
      if (!coverUrl) coverUrl = s
      if (s.includes('.jpeg')) { coverUrl = s; break }
    }
    return {
      durationSeconds,
      views: toNum(stat.view_count),
      likes: toNum(stat.like_count),
      comments: toNum(stat.comment_count),
      shares: toNum(stat.share_count),
      retentionPct: ratioToPct(stat.completion_rate),
      play5sPct: ratioToPct(stat.completion_rate_5s),
      drop2sPct: ratioToPct(stat.bounce_rate_2s),
      avgPlayRatioPct: ratioToPct(stat.avg_view_proportion),
      avgPlaySeconds: toNum(stat.avg_view_second),
      favoriteRatePct: ratioToPct(stat.favorite_rate),
      dislikeRatePct: ratioToPct(stat.dislike_rate),
      coverUrl,
      likeRate: toNum(stat.like_rate),
      shareRate: toNum(stat.share_rate),
      commentRate: toNum(stat.comment_rate),
      subscribeCount: toNum(stat.subscribe_count),
      unsubscribeCount: toNum(stat.unsubscribe_count),
      coverCtr: ratioToPct(stat.cover_click_rate),
      homepageVisitCount: toNum(stat.homepage_visit_count),
    }
  },
})

// B站 数据中心(/x/web/data/...,主要是 archive_diagnose/compare):
//   坑 1:bvid 是公开稳定 ID,优先用它而不是 aid。
//   坑 2:stat.not_ready_field 列出"还没算出来"的字段名 —— 它们的值此时是占位 0,
//        必须当作 null,否则会把"未就绪"写成 0% 污染数据。
//   坑 3:百分比是万分比(5315 表示 53.15%),÷100 得标准百分数。
function bilibiliRateToPct(v: unknown): number | null {
  const n = toNum(v)
  if (n === null) return null
  // 万分比 0~10000,÷100 得百分数。容错:已经是 0~100 的就原样。
  const pct = n > 100 ? n / 100 : n
  return Math.round(pct * 100) / 100
}

const normalizeBilibili = createNormalizer({
  platform: 'B站',
  pickRows: (root) => {
    const data = (root.data ?? root) as Obj
    return Array.isArray(data.list) ? (data.list as unknown[]) : []
  },
  required: {
    platformVideoId: (item) => toStr(item.bvid ?? item.aid),
    title: (item) => toStr(item.title),
    publishedAt: (item) => toLocalDateTime(item.pubtime),
  },
  optional: (item) => {
    const stat = (item.stat ?? {}) as Obj
    const notReady = new Set(Array.isArray(stat.not_ready_field) ? (stat.not_ready_field as string[]) : [])
    const pickRaw = (key: string): unknown => (notReady.has(key) ? null : stat[key])
    const dur = toNum(item.duration)
    return {
      durationSeconds: dur === null ? null : Math.round(dur),
      views: toNum(stat.play),
      likes: toNum(stat.like),
      comments: toNum(stat.comment),
      shares: toNum(stat.share),
      retentionPct: bilibiliRateToPct(pickRaw('full_play_ratio')),
      avgPlaySeconds: toNum(pickRaw('avg_play_time') ?? pickRaw('avg_play_time_int')),
      coverUrl: toStr(item.cover),
      // 涨粉数 / 掉粉数,both 走 not_ready 兜底
      subscribeCount: toNum(pickRaw('total_new_attention_cnt')),
      unsubscribeCount: toNum(pickRaw('unfollow')),
    }
  },
})

// 视频号助手 post_list 的比率字段(fullPlayRate / fastFlipRate)是 0~1 浮点,与抖音一致;
// fastFlipRate(快滑率)语义最接近"短时间内划走",映射到 drop2sPct。
const normalizeWeixin = createNormalizer({
  platform: '视频号',
  pickRows: (root) => {
    const data = (root.data ?? root) as Obj
    return Array.isArray(data.list) ? (data.list as unknown[]) : []
  },
  required: {
    platformVideoId: (item) => toStr(item.objectId),
    title: (item) => {
      const desc = (item.desc ?? {}) as Obj
      const shortTitleArr = Array.isArray(desc.shortTitle) ? (desc.shortTitle as unknown[]) : []
      const firstShort = shortTitleArr[0] && typeof shortTitleArr[0] === 'object'
        ? (shortTitleArr[0] as Obj).shortTitle
        : null
      return toStr(firstShort ?? desc.description)
    },
    publishedAt: (item) => toLocalDateTime(item.createTime),
  },
  optional: (item) => {
    const desc = (item.desc ?? {}) as Obj
    const media = Array.isArray(desc.media) ? (desc.media as unknown[]) : []
    const firstMedia = media[0] && typeof media[0] === 'object' ? (media[0] as Obj) : null
    const dur = toNum(firstMedia?.videoPlayLen)
    return {
      durationSeconds: dur === null ? null : Math.round(dur),
      views: toNum(item.readCount),
      likes: toNum(item.likeCount),
      comments: toNum(item.commentCount),
      shares: toNum(item.forwardCount),
      retentionPct: ratioToPct(item.fullPlayRate),
      avgPlaySeconds: toNum(item.avgPlayTimeSec),
      drop2sPct: ratioToPct(item.fastFlipRate),
    }
  },
})

const normalizeKuaishou = createNormalizer({
  platform: '快手',
  pickRows: (root) => {
    const data = (root.data ?? {}) as Obj
    const photoList = (data.photoList ?? {}) as Obj
    return Array.isArray(photoList.photoItems) ? (photoList.photoItems as unknown[]) : []
  },
  required: {
    platformVideoId: (item) => toStr(item.photoId),
    title: (item) => toStr(item.title),
    publishedAt: (item) => toLocalDateTime(item.publishTime),
  },
  optional: (item) => {
    // duration 是毫秒(样本:152633ms),÷1000 取整成秒
    const durMs = toNum(item.duration)
    return {
      durationSeconds: durMs === null ? null : Math.round(durMs / 1000),
      views: toNum(item.playCount),
      likes: toNum(item.likeCount),
      comments: toNum(item.commentCount),
      retentionPct: ratioToPct(item.fpr),
      coverUrl: toStr(item.cover),
      // followCount = 该视频带来的涨粉数,语义对应 subscribeCount
      subscribeCount: toNum(item.followCount),
    }
  },
})

const NORMALIZERS: Record<Platform, (raw: unknown) => PublishedVideoUpsert[]> = {
  抖音: normalizeDouyin,
  B站: normalizeBilibili,
  视频号: normalizeWeixin,
  快手: normalizeKuaishou,
}

export function normalize(capture: RawCapture): PublishedVideoUpsert[] {
  try {
    return NORMALIZERS[capture.platform](capture.raw)
  } catch (e) {
    console.warn('[auteur] normalize failed', capture.platform, capture.origin, e)
    return []
  }
}
