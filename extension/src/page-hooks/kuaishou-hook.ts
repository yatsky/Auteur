// 快手创作中心 cp.kuaishou.com:只拦数据分析页 /rest/cp/creator/analysis/pc/photo/list,
// 它是作品管理列表的超集(独有 fpr 完播率 / cover 封面 URL / followCount 该视频涨粉数)。
// 数据更新依赖用户打开"数据分析"页;只在"作品管理"页操作时数据会停滞。
// 详情接口 /single/... 的 photoId 在 POST body 里,响应不含,需要时再单独接。
import { installHook } from './_install'

installHook({
  platform: '快手',
  flag: '__auteurKuaishouHooked',
  patterns: [
    /\/rest\/cp\/creator\/analysis\/pc\/photo\/list/i,
  ],
})
