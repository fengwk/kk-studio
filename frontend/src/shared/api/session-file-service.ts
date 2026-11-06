import { executeFetch, request, url } from '@/shared/api/client'
import type {
  HarnessSessionFileContentDTO,
  HarnessSessionFileListDTO,
} from '@/shared/api/generated/contracts'

/** 文本窗口坐标；offset 与 columnOffset 从 1 开始，limit 默认且最大 2000 行。 */
export interface SessionFileTextWindow {
  offset?: number
  limit?: number
  columnOffset?: number
}

/** 下载结果：完整字节与后端给出的附件文件名。 */
interface SessionFileDownload {
  blob: Blob
  fileName: string
}

/** 列出会话目录的直属条目；省略 path 表示会话根目录。 */
export function listSessionFiles(
  sessionId: string,
  path?: string,
): Promise<HarnessSessionFileListDTO> {
  return request('GET /api/harness/sessions/{sessionId}/files', {
    path: { sessionId },
    query: { path },
  })
}

/** 读取会话文件的文本窗口；缺省坐标由服务端套用默认值。 */
export function readSessionFileContent(
  sessionId: string,
  path: string,
  window: SessionFileTextWindow = {},
): Promise<HarnessSessionFileContentDTO> {
  return request('GET /api/harness/sessions/{sessionId}/files/content', {
    path: { sessionId },
    query: {
      path,
      offset: window.offset,
      limit: window.limit,
      columnOffset: window.columnOffset,
    },
  })
}

/** 下载完整字节；失败响应按 problem+json 解析以保留错误详情，成功时保留附件文件名。 */
export function downloadSessionFile(
  sessionId: string,
  path: string,
  signal?: AbortSignal,
): Promise<SessionFileDownload> {
  return executeFetch<SessionFileDownload>(
    url('GET /api/harness/sessions/{sessionId}/files/download', {
      path: { sessionId },
      query: { path },
    }),
    {
      signal,
      readResponse: async (response) => ({
        blob: await response.blob(),
        fileName: attachmentFileName(response.headers.get('Content-Disposition'), path),
      }),
    },
  )
}

/**
 * 附件文件名优先取 RFC 5987 的 `filename*`，其次取普通 `filename`，
 * 最后回退到请求路径末段。Content-Disposition 缺失时仍给出可下载的名字。
 */
export function attachmentFileName(disposition: string | null, fallbackPath: string): string {
  if (disposition != null) {
    const encoded = /filename\*\s*=\s*UTF-8''([^;]+)/i.exec(disposition)
    if (encoded != null) {
      try {
        return decodeURIComponent(encoded[1]!.trim())
      } catch {
        // 非法的百分号编码不足以否决整个响应，继续读取普通 filename。
      }
    }
    const plain = /filename\s*=\s*"([^"]+)"/i.exec(disposition)
      ?? /filename\s*=\s*([^;]+)/i.exec(disposition)
    if (plain != null) {
      return plain[1]!.trim()
    }
  }
  const slash = fallbackPath.lastIndexOf('/')
  return slash < 0 ? fallbackPath : fallbackPath.slice(slash + 1)
}

/** 会话文件浏览与下载共用的调用入口。 */
export const sessionFileService = {
  listSessionFiles,
  readSessionFileContent,
  downloadSessionFile,
}
