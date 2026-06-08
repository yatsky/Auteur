// 抖音创作者中心:只拦 /web/api/creator/。该网关 list 已一次性返回 id/description/
// create_time/video_info.duration/cover.url_list/metrics(view/like/comment/share/
// completion/bounce/avg_view/like_rate/subscribe/cover_click/...)全部所需字段。
import { installHook } from './_install'

installHook({
  platform: '抖音',
  flag: '__auteurDouyinHooked',
  patterns: [
    /\/web\/api\/creator\//i,
  ],
})
