import {
  apiBaseUrl,
  apiClient,
  executeFetch,
  type HttpClient,
} from '@/shared/api/client'
import type {
  HarnessSessionFileContentDTO,
  HarnessSessionFileListDTO,
} from '@/shared/api/generated/contracts'

/** 会话归属：列目录、读文本与下载都必须携带产品 owner 供后端授权。 */
export interface SessionFileOwner {
  kind: string
  id: string
}

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

/** 会话文件只读 HTTP：列目录、文本窗口与下载；写操作只由文件工具发起。 */
export function createSessionFileService(
  client: HttpClient = apiClient,
  fetchImpl: typeof fetch = fetch,
) {
  const filesUrl = (sessionId: string): string =>
    `/sessions/${encodeURIComponent(sessionId)}/files`

  return {
    listSessionFiles: (
      sessionId: string,
      owner: SessionFileOwner,
      path?: string,
    ): Promise<HarnessSessionFileListDTO> =>
      client.get(filesUrl(sessionId), {
        params: { ownerKind: owner.kind, ownerId: owner.id, path },
      }),

    readSessionFileContent: (
      sessionId: string,
      owner: SessionFileOwner,
      path: string,
      window: SessionFileTextWindow = {},
    ): Promise<HarnessSessionFileContentDTO> =>
      client.get(`${filesUrl(sessionId)}/content`, {
        params: {
          ownerKind: owner.kind,
          ownerId: owner.id,
          path,
          offset: window.offset,
          limit: window.limit,
          columnOffset: window.columnOffset,
        },
      }),

    /** 下载完整字节；失败响应按 RFC 9457 problem+json 解析以保留错误详情。 */
    downloadSessionFile: async (
      sessionId: string,
      owner: SessionFileOwner,
      path: string,
      signal?: AbortSignal,
    ): Promise<SessionFileDownload> => {
      const params = new URLSearchParams({
        ownerKind: owner.kind,
        ownerId: owner.id,
        path,
      })
      return executeFetch<SessionFileDownload>(
        `${apiBaseUrl}${filesUrl(sessionId)}/download?${params.toString()}`,
        {
          fetchImpl,
          signal,
          readResponse: async (response) => ({
            blob: await response.blob(),
            fileName: attachmentFileName(response.headers.get('Content-Disposition'), path),
          }),
        },
      )
    },
  }
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

export const sessionFileService = createSessionFileService()
