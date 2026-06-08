// B站 创作中心(member.bilibili.com)只拦数据中心 /x/web/data/(archive_diagnose/compare 等富数据接口)。
// 数据更新依赖用户打开"数据中心"页;只在"稿件管理"页晃时数据会停滞。
// 刚发布几小时的视频在 stat.not_ready_field 里大量字段未就绪,normalize 已把它们当 null。
import { installHook } from './_install'

installHook({
  platform: 'B站',
  flag: '__auteurBilibiliHooked',
  patterns: [
    /\/x\/web\/data\//i,
  ],
})
