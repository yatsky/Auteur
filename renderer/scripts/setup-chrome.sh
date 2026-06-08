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
  "Darwin arm64") ARCH="mac-arm64" ;;
  "Darwin x86_64") ARCH="mac-x64" ;;
  "Linux x86_64") ARCH="linux64" ;;
  *) echo "❌ 不支持的平台: $(uname -sm)"; exit 1 ;;
esac

DOWNLOAD_URL="https://storage.googleapis.com/chrome-for-testing-public/${CHROME_VERSION}/${ARCH}/chrome-headless-shell-${ARCH}.zip"
CHROME_DIR=".chrome/chrome-headless-shell-${ARCH}"
CHROME_BIN="${CHROME_DIR}/chrome-headless-shell"

if [ -x "${CHROME_BIN}" ]; then
  echo "✅ Chrome Headless Shell 已存在: ${CHROME_BIN}"
  exit 0
fi

echo "📥 下载 Chrome Headless Shell ${CHROME_VERSION} (~80MB 压缩 / 解压后约 193MB)..."
mkdir -p .chrome
TMP_ZIP=$(mktemp -t chs.XXXXXX).zip
curl -L --progress-bar "${DOWNLOAD_URL}" -o "${TMP_ZIP}"

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
