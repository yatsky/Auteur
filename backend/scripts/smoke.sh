#!/usr/bin/env bash
# 端到端冒烟：brainstorm → script → factcheck → storyboard → images(limit) → audit
#
# 用法：
#   ./smoke.sh                        # 默认 freeform 预设,brainstorm 3 候选 + 生 1 张图
#   PRESET=lifecopy ./smoke.sh        # 用 lifecopy 进阶预设(需自行在 UI 建好对应 preset 行)
#   IMAGE_LIMIT=4 ./smoke.sh          # 多生几张图
#   SKIP_IMAGE=1 ./smoke.sh           # 完全跳过生图+图审（最贵的两步，新部署强烈建议先这么跑）
#   BASE=http://host:port ./smoke.sh  # 指向别的环境
#   N_TOPICS=10 ./smoke.sh            # brainstorm 拉多少候选
#
# 退出码：任一步 HTTP 非 2xx 或返回结构异常即非 0 退出。
set -euo pipefail

BASE="${BASE:-http://localhost:8082}"
PRESET="${PRESET:-freeform}"
N_TOPICS="${N_TOPICS:-3}"
IMAGE_LIMIT="${IMAGE_LIMIT:-1}"
SKIP_IMAGE="${SKIP_IMAGE:-0}"

step=0
section() {
    step=$((step+1))
    echo
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "[$step] $1"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
}

# 调用接口并打印耗时；body 写到 $tmp 文件，调用方用 jq 解析
# 用法：F=$(call METHOD PATH [BODY_JSON])  → 拿到 tmp 文件路径
# 注意：所有人看的日志走 stderr，stdout 只输出 tmp 文件路径，避免被 $(...) 捕获污染
call() {
    local method="$1" path="$2" body="${3:-}"
    local url="${BASE}${path}"
    local tmp; tmp=$(mktemp)
    local t0 t1 dt http
    t0=$(date +%s)
    if [[ -n "$body" ]]; then
        http=$(curl -sS -o "$tmp" -w "%{http_code}" -X "$method" "$url" \
            -H "Content-Type: application/json" -d "$body")
    else
        http=$(curl -sS -o "$tmp" -w "%{http_code}" -X "$method" "$url")
    fi
    t1=$(date +%s); dt=$((t1-t0))
    echo "  ${method} ${path}  →  HTTP ${http}  (${dt}s)" >&2
    if [[ "$http" != 2* ]]; then
        echo "  ✗ 非 2xx，body 头 400 字符：" >&2
        head -c 400 "$tmp" >&2; echo >&2
        rm -f "$tmp"
        exit 1
    fi
    echo "$tmp"
}

require_jq_nonempty() {
    local file="$1" expr="$2" label="$3"
    if ! jq -e "$expr" "$file" >/dev/null 2>&1; then
        echo "  ✗ ${label} 校验失败，body 头 400 字符：" >&2
        head -c 400 "$file" >&2; echo >&2
        exit 1
    fi
}

T0_TOTAL=$(date +%s)
echo "Smoke target : $BASE"
echo "PRESET=$PRESET  N_TOPICS=$N_TOPICS  IMAGE_LIMIT=$IMAGE_LIMIT  SKIP_IMAGE=$SKIP_IMAGE"

# ─────────────────────────────── 0. resolve preset id
section "resolve preset  ($PRESET)"
F=$(call GET "/api/presets/by-name/${PRESET}")
require_jq_nonempty "$F" '.id != null' "preset lookup"
PRESET_ID=$(jq -r '.id' "$F")
echo "  ✓ presetId=$PRESET_ID"
rm -f "$F"

# ─────────────────────────────── 1. brainstorm
section "brainstorm  (n=$N_TOPICS)"
F=$(call POST /api/topics/brainstorm "{\"n\":${N_TOPICS},\"presetId\":${PRESET_ID}}")
require_jq_nonempty "$F" 'type=="array" and length>0' "brainstorm"
TOPIC_ID=$(jq -r '.[0].id' "$F")
TOPIC_TITLE=$(jq -r '.[0].title' "$F")
TOPIC_DIM=$(jq -r '.[0].dynasty // .[0].genre // "-"' "$F")
TOPIC_SCORE=$(jq -r '.[0].potentialScore // "-"' "$F")
echo "  ✓ topicId=$TOPIC_ID  [$TOPIC_DIM]  $TOPIC_TITLE  (score=$TOPIC_SCORE)"
rm -f "$F"

# ─────────────────────────────── 2. script
section "generate script"
F=$(call POST "/api/topics/${TOPIC_ID}/scripts/generate")
require_jq_nonempty "$F" '.id != null' "script"
SCRIPT_ID=$(jq -r '.id' "$F")
DURATION=$(jq -r '.durationSeconds // "-"' "$F")
echo "  ✓ scriptId=$SCRIPT_ID  duration=${DURATION}s"
rm -f "$F"

# ─────────────────────────────── 3. factcheck
section "factcheck"
F=$(call POST "/api/scripts/${SCRIPT_ID}/factcheck")
require_jq_nonempty "$F" 'type=="array"' "factcheck"
ISSUE_COUNT=$(jq 'length' "$F")
HIGH=$(jq '[.[] | select(.severity=="HIGH")] | length' "$F" 2>/dev/null || echo 0)
echo "  ✓ issues=$ISSUE_COUNT  (HIGH=$HIGH)"
rm -f "$F"

# ─────────────────────────────── 4. voice (async; storyboard 需要 SRT 才能跑)
section "voice + subtitle  (async)"
F=$(call POST "/api/scripts/${SCRIPT_ID}/voice/generate-async" '{"markFinal":true}')
require_jq_nonempty "$F" '.runId != null' "voice generate-async"
RUN_ID=$(jq -r '.runId' "$F")
rm -f "$F"
echo "  → runId=$RUN_ID  polling..."
for i in $(seq 1 120); do
    sleep 3
    F=$(call GET "/api/runs/${RUN_ID}")
    STATUS=$(jq -r '.status' "$F")
    rm -f "$F"
    case "$STATUS" in
        DONE)      echo "  ✓ voice run DONE (~$((i*3))s)"; break ;;
        FAILED|CANCELLED)
            echo "  ✗ voice run $STATUS"; exit 1 ;;
    esac
    if [[ $i -eq 120 ]]; then
        echo "  ✗ voice run 6 分钟未完成,放弃"; exit 1
    fi
done

# ─────────────────────────────── 5. storyboard
section "storyboard"
F=$(call POST "/api/scripts/${SCRIPT_ID}/storyboard/generate")
require_jq_nonempty "$F" 'type=="array" and length>0' "storyboard"
SHOT_COUNT=$(jq 'length' "$F")
HAS_NEG=$(jq '[.[] | select(.negativePrompt != null and .negativePrompt != "")] | length' "$F")
HAS_STYLE=$(jq '[.[] | select(.styleTag != null and .styleTag != "")] | length' "$F")
echo "  ✓ shots=$SHOT_COUNT  (negativePrompt 非空 ${HAS_NEG}/${SHOT_COUNT}, styleTag 非空 ${HAS_STYLE}/${SHOT_COUNT})"
if [[ "$HAS_NEG" != "$SHOT_COUNT" || "$HAS_STYLE" != "$SHOT_COUNT" ]]; then
    echo "  ⚠ 注意：negativePrompt/styleTag 应由 service 层注入，全部应为非空"
fi
rm -f "$F"

# ─────────────────────────────── 5/6. images + audit （可跳过）
if [[ "$SKIP_IMAGE" == "1" ]]; then
    echo
    echo "(SKIP_IMAGE=1, 跳过生图与图审)"
    IMG_COUNT="-"
    PASS_COUNT="-"
else
    section "image gen  (limit=$IMAGE_LIMIT)"
    F=$(call POST "/api/scripts/${SCRIPT_ID}/images/generate?limit=${IMAGE_LIMIT}")
    require_jq_nonempty "$F" 'type=="array"' "image gen"
    IMG_COUNT=$(jq 'length' "$F")
    SAMPLE_URL=$(jq -r '.[0].fileUrl // "-"' "$F")
    echo "  ✓ generated=$IMG_COUNT  sample=$(echo "$SAMPLE_URL" | head -c 80)..."
    rm -f "$F"

    section "image audit"
    F=$(call POST "/api/scripts/${SCRIPT_ID}/images/audit")
    require_jq_nonempty "$F" 'type=="array"' "image audit"
    PASS_COUNT=$(jq '[.[] | select(.reviewDecision=="PASS")] | length' "$F" 2>/dev/null || echo "?")
    AUDITED=$(jq 'length' "$F")
    AVG_SCORE=$(jq '[.[] | (.reviewScore // 0) | tonumber] | (add / length) | floor' "$F" 2>/dev/null || echo "?")
    echo "  ✓ audited=$AUDITED  PASS=${PASS_COUNT}/${AUDITED}  avgScore=${AVG_SCORE}"
    rm -f "$F"
fi

# ─────────────────────────────── summary
T1_TOTAL=$(date +%s)
echo
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "✅ SMOKE PASS   total $((T1_TOTAL-T0_TOTAL))s"
echo "   topicId=$TOPIC_ID  scriptId=$SCRIPT_ID  shots=$SHOT_COUNT  images=${IMG_COUNT}  pass=${PASS_COUNT}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
