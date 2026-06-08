import path from 'node:path'
import { Config } from '@remotion/cli/config'

Config.setVideoImageFormat('jpeg')
Config.setOverwriteOutput(true)
Config.setConcurrency(4)
Config.setChromiumOpenGlRenderer('angle')

// 用项目自带的 chrome-headless-shell(由 scripts/setup-chrome.sh 安装到 .chrome/)
// 不用系统 Chrome:它读你的 profile + 代理扩展,localhost 也会被代理拦截导致渲染失败
// 不用 Remotion 默认下载:它下到 ~/Downloads/ 会被清理,且 Gatekeeper 会弹窗删主程序
const arch = process.platform === 'darwin' && process.arch === 'arm64'
  ? 'mac-arm64'
  : process.platform === 'darwin'
    ? 'mac-x64'
    : 'linux64'

const chromeBin =
  process.env.REMOTION_CHROME ??
  path.resolve(
    process.cwd(),
    `.chrome/chrome-headless-shell-${arch}/chrome-headless-shell`,
  )
Config.setBrowserExecutable(chromeBin)

