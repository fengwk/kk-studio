/** 会话文件面板的纯展示逻辑：vfs 路径、文本窗口解析与文件类型判断。 */

/** 文本窗口每页行数；服务端上限 2000，较小分页便于逐页回看。 */
export const SESSION_FILE_TEXT_PAGE_LINES = 500

/** 会话内相对路径拼成 vfs 坐标；目录 entry 的 path 自带结尾斜杠。 */
export function sessionFilePath(sessionId: string, relativePath: string): string {
  return `vfs:/sessions/${sessionId}/${relativePath.replace(/^\/+/, '')}`
}

/** 后备图片预览的扩展名集合；其它文件一律先按文本窗口读取。 */
const IMAGE_EXTENSIONS = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'avif', 'ico', 'svg'])

export function isImageFileName(name: string): boolean {
  const dot = name.lastIndexOf('.')
  if (dot < 0 || dot === name.length - 1) {
    return false
  }
  return IMAGE_EXTENSIONS.has(name.slice(dot + 1).toLowerCase())
}

export interface SessionFileTextWindow {
  /** 服务端 range 头，形如 `1:1-2:9`；空窗口为 null。 */
  range: string | null
  truncated: boolean
  /** 服务端给出的下一个窗口起点；没有更多内容时为 null。 */
  next: { offset: number; columnOffset: number } | null
  /** 去掉元数据与截断标记后的编号正文。 */
  body: string
}

const HEADER_LINE = /^(path|ends_with_newline|range|truncated|truncation_reason|next|lsp): ?(.*)$/
const NEXT_POSITION = /^(\d+):(\d+)$/
const TRUNCATION_MARKER = '[TRUNCATED:'

/**
 * 解析 `read` 工具同源的有界文本窗口：头部元数据、空行、`行号|正文`，
 * 截断时末尾附带 `[TRUNCATED: ...]` 标记。格式不符时保留整段文本，绝不丢弃内容。
 */
export function parseSessionFileTextWindow(text: string): SessionFileTextWindow {
  const lines = text.split('\n')
  let index = 0
  let range: string | null = null
  let truncated = false
  let next: SessionFileTextWindow['next'] = null
  while (index < lines.length) {
    const line = lines[index]!
    if (line === '') {
      index += 1
      break
    }
    const header = HEADER_LINE.exec(line)
    if (header == null) {
      break
    }
    const value = header[2] ?? ''
    if (header[1] === 'range') {
      range = value === 'empty' ? null : value
    } else if (header[1] === 'truncated') {
      truncated = value === 'yes'
    } else if (header[1] === 'next') {
      const position = NEXT_POSITION.exec(value)
      if (position != null) {
        next = { offset: Number(position[1]), columnOffset: Number(position[2]) }
      }
    }
    index += 1
  }
  const body = lines.slice(index)
  while (body.length > 0 && body[body.length - 1]!.startsWith(TRUNCATION_MARKER)) {
    body.pop()
  }
  while (body.length > 0 && body[body.length - 1] === '') {
    body.pop()
  }
  return { range, truncated, next, body: body.join('\n') }
}

/** 解析 wire 上的十进制字节数；非法值返回 null，由调用方省略展示。 */
export function parseSizeBytes(value: string | null | undefined): number | null {
  if (value == null || !/^(0|[1-9][0-9]*)$/.test(value)) {
    return null
  }
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) ? parsed : null
}

/** 更新时间为 epoch 毫秒，格式化为本地 `YYYY-MM-DD HH:mm`；非法值返回 null。 */
export function formatSessionFileTime(value: string | null | undefined): string | null {
  if (value == null || !/^(0|[1-9][0-9]*)$/.test(value)) {
    return null
  }
  const date = new Date(Number(value))
  if (!Number.isFinite(date.getTime())) {
    return null
  }
  return `${String(date.getFullYear()).padStart(4, '0')}-${String(date.getMonth() + 1).padStart(2, '0')}`
    + `-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}`
    + `:${String(date.getMinutes()).padStart(2, '0')}`
}
