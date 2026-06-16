#!/bin/bash
# 下载 chrome-headless-shell 到项目 .chrome/ 并清除 macOS 隔离标记
# 之所以不用 Remotion 默认下载:它会下到 ~/Downloads/(会被自动清理),
# 而且 macOS Gatekeeper 会弹窗"是否删除"导致主可执行文件被误删。
set -e

cd "$(dirname "$0")/.."

# 与 Remotion 4.0.469 内置版本对齐;升级 Remotion 后日志里看到新版本号就改这里
CHROME_VERSION="149.0.7790.0"

# 检测架构
case "$(uname -sm)" in
  "Darwin arm64") ARCH="mac-arm64"; CHROME_EXT="" ;;
  "Darwin x86_64") ARCH="mac-x64"; CHROME_EXT="" ;;
  "Linux x86_64") ARCH="linux64"; CHROME_EXT="" ;;
  MINGW*|MSYS*|CYGWIN*) ARCH="win64"; CHROME_EXT=".exe" ;;
  *) echo "❌ 不支持的平台: $(uname -sm)"; exit 1 ;;
esac

DOWNLOAD_BASE="${CHROME_FOR_TESTING_MIRROR:-https://storage.googleapis.com/chrome-for-testing-public}"
DOWNLOAD_URL="${DOWNLOAD_BASE}/${CHROME_VERSION}/${ARCH}/chrome-headless-shell-${ARCH}.zip"
# 国内镜像(npmmirror 全量同步 chrome-for-testing)。主源失败时自动 fallback。
MIRROR_URL="https://registry.npmmirror.com/-/binary/chrome-for-testing/${CHROME_VERSION}/${ARCH}/chrome-headless-shell-${ARCH}.zip"
CHROME_DIR=".chrome/chrome-headless-shell-${ARCH}"
CHROME_BIN="${CHROME_DIR}/chrome-headless-shell${CHROME_EXT}"

if [ -x "${CHROME_BIN}" ]; then
  echo "✅ Chrome Headless Shell 已存在: ${CHROME_BIN}"
  exit 0
fi

echo "📥 下载 Chrome Headless Shell ${CHROME_VERSION} (~80MB 压缩 / 解压后约 193MB)..."
mkdir -p .chrome
TMP_ZIP=$(mktemp -t chs.XXXXXX).zip

# --connect-timeout 15:Google CDN 在国内通常 SYN 都过不去,15s 内连不上立即落镜像,
# 不要等默认的 SSL 握手 timeout(单次 ~5min)。--fail:HTTP 4xx/5xx 也算失败。
echo "↓ 主源: ${DOWNLOAD_URL}"
if ! curl --connect-timeout 15 --fail -L --progress-bar "${DOWNLOAD_URL}" -o "${TMP_ZIP}"; then
  echo "⚠️ 主源失败,改用国内镜像: ${MIRROR_URL}"
  curl --connect-timeout 30 --fail -L --progress-bar "${MIRROR_URL}" -o "${TMP_ZIP}"
fi

echo "📦 解压到 .chrome/..."
unzip -q "${TMP_ZIP}" -d .chrome/
rm "${TMP_ZIP}"

if [ "$(uname)" = "Darwin" ]; then
  echo "🔓 清除 macOS quarantine 隔离标记(防止 Gatekeeper 弹窗)..."
  xattr -dr com.apple.quarantine .chrome/ 2>/dev/null || true
fi

if [ ! -x "${CHROME_BIN}" ]; then
  echo "❌ 安装失败: 找不到 ${CHROME_BIN}"
  exit 1
fi

SIZE=$(du -sh "${CHROME_DIR}" | cut -f1)
echo "✅ 完成 — ${CHROME_BIN} (${SIZE})"
