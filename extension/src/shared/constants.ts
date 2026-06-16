/**
 * "新鲜数据"的时间窗口:24h。两处用途共享同一个值:
 *  - popup:平台采集状态 < 24h 显 done(绿点),超出显 stale(黄点)。
 *  - throttle:节流表里 24h 前的条目走 cleanupOldEntries 清掉。
 */
export const FRESH_WINDOW_MS = 24 * 60 * 60 * 1000
