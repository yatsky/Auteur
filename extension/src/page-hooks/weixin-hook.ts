// 视频号助手(channels.weixin.qq.com)统计接口:/mmfinderassistant-bin/statistic/
// 下 post_list 含 per-video KPI;new_post_total_data 是频道级聚合,normalize 返回 [] 跳过。
import { installHook } from './_install'

installHook({
  platform: '视频号',
  flag: '__auteurWeixinHooked',
  patterns: [
    /\/mmfinderassistant-bin\/statistic\//i,
  ],
})
