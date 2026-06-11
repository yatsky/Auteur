# Demo Fixtures 数据填充指南

这个目录将存放从真实流水线导出的 mock 数据 JSON 文件。

## 当前状态

⚠️ **空目录占位** — 等用户跑完一条完整流水线 + 执行 `backend/scripts/export-demo-fixtures.sh` 后,这里会被自动填充。

## 计划生成的文件

```
fixtures/
├── presets.json            # GET /presets
├── preset-freeform.json    # GET /presets/by-name/freeform
├── topics.json             # GET /topics?status=DRAFT
├── topic-1.json            # GET /topics/1
├── topic-1-lineage.json    # GET /topics/1/lineage
├── brainstorm-result.json  # POST /topics/brainstorm
├── script-1.json           # GET /scripts/1
├── storyboard-1.json       # GET /scripts/1/storyboard(20-28 条 shot)
├── images-1.json           # GET /scripts/1/images(每条带 url 指向 /demo-assets/images/...)
├── voice-1.json            # GET /scripts/1/voice
├── videos-1.json           # GET /scripts/1/videos
├── covers-1.json           # GET /scripts/1/covers
├── bgm-recommend-1.json    # GET /bgm/scripts/1/recommend
├── agent-tools.json        # GET /agent/tools
├── agent-sessions.json     # GET /agent/sessions
├── agent-messages-1.json   # GET /agent/sessions/1/messages
├── insights-top-bottom.json
└── insights-weekly.json
```

二进制资源(图片 / 音频 / 视频)由导出脚本拷到 `frontend/public/demo-assets/`,通过 fixture 里的 `url: "/demo-assets/images/shot-01.jpg"` 引用。

## 填充流程(用户操作)

```bash
# 1) 起服务
docker compose up -d

# 2) 浏览器:localhost:5174 → 系统设置 → 填 LLM key
#    然后真跑一条完整流水线:选题脑暴 → 脚本 → 分镜 → 生图 → 配音 → 视频组装

# 3) 跑导出脚本
bash backend/scripts/export-demo-fixtures.sh <topic-id>

# 4) 脚本会:
#    - 从 docker MySQL dump 关键表为 JSON,落到 frontend/src/demo/fixtures/
#    - 拷贝 backend/storage/ 下的图片/音频/视频到 frontend/public/demo-assets/
#    - 输出当前 demo 站可演示的 endpoints 清单
```

之后我把这些 JSON 接进 `frontend/src/demo/fixtures.ts` 的 fixturesTable,demo 站就能跑完整演示流程了。
