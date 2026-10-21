/**
 * Environment 凭据复制：只在用户显式点击时调用，不缓存、不落盘。
 *
 * 优先使用异步 Clipboard API；在非安全上下文或权限被拒时回退到临时 textarea + execCommand，
 * 并在任何失败路径清理临时节点。
 */
export async function copyTextToClipboard(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // 继续走兜底逻辑
  }
  let area: HTMLTextAreaElement | null = null
  try {
    area = document.createElement('textarea')
    area.value = text
    area.setAttribute('readonly', '')
    area.style.position = 'fixed'
    area.style.left = '-9999px'
    document.body.appendChild(area)
    area.select()
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    area?.remove()
  }
}
