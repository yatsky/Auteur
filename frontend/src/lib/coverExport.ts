// 同源 hero 图不会让 canvas taint,toBlob 能正常输出 PNG。
export function downloadCoverPng(canvas: HTMLCanvasElement, filename: string): Promise<void> {
  return new Promise((resolve, reject) => {
    canvas.toBlob((blob) => {
      if (!blob) {
        reject(new Error('canvas.toBlob returned null — 可能是 hero 图跨域导致 canvas tainted'))
        return
      }
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = filename
      document.body.appendChild(a)
      a.click()
      a.remove()
      // 略延一帧再 revoke,Safari 上立刻 revoke 会导致下载失败
      setTimeout(() => URL.revokeObjectURL(url), 1500)
      resolve()
    }, 'image/png')
  })
}

export function safeFilename(s: string): string {
  // 去掉 path 分隔符 + 控制字符,保留中文
  return s.replace(/[\/\\\:\*\?\"<>\|]/g, '_').slice(0, 120)
}
