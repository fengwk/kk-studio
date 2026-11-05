export interface CopyTextOptions {
  /**
   * 异步复制失败后、写入 DOM 兜底前再次确认调用方仍然有效。
   * 例如组件已卸载时不应再插入 textarea 抢走焦点。
   */
  shouldFallback?: () => boolean
}

/**
 * 复制文本，只在用户显式点击时调用，不缓存、不落盘。
 *
 * 优先使用异步 Clipboard API；在非安全上下文或权限被拒时回退到临时 textarea + execCommand，
 * 并在任何失败路径清理临时节点。
 */
export async function copyTextToClipboard(
  text: string,
  options?: CopyTextOptions,
): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // 继续走兜底逻辑
  }
  if (options?.shouldFallback && !options.shouldFallback()) {
    return false
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
