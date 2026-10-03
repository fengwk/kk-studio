/** 导出 YAML 的固定下载文件名。 */
export const CONFIG_SYNC_EXPORT_FILENAME = 'kk-studio-config.yaml'

/**
 * 把导出 YAML 触发为浏览器下载：瞬时 objectURL + 隐藏 anchor，点击后立即撤销 URL，
 * 不把 YAML 内容写入 DOM 或页面状态。
 */
export function downloadConfigYaml(yaml: string, filename = CONFIG_SYNC_EXPORT_FILENAME): void {
  const blob = new Blob([yaml], { type: 'application/yaml' })
  const url = URL.createObjectURL(blob)
  try {
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = filename
    anchor.rel = 'noopener'
    anchor.style.display = 'none'
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
  } finally {
    URL.revokeObjectURL(url)
  }
}
