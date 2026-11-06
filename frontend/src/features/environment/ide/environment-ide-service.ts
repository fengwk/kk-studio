import { request } from '@/shared/api/client'
import type {
  EnvironmentIdeChangeDirectoryDTO,
  EnvironmentIdeCloseViewDTO,
  EnvironmentIdeDirectoryListingDTO,
  EnvironmentIdeOpenViewDTO,
  EnvironmentIdeViewBindingDTO,
  EnvironmentIdeViewDTO,
} from '@/shared/api/generated/contracts'

/** 打开或恢复 view：claimId 与 mode 由调用方持有，失败后只允许按原 claimId 对账。 */
export function openIdeView(
  environmentId: string,
  body: EnvironmentIdeOpenViewDTO,
): Promise<EnvironmentIdeViewDTO> {
  return request('POST /api/ide/environments/{environmentId}/views', {
    path: { environmentId },
    body,
  })
}

export function changeIdeViewDirectory(
  environmentId: string,
  viewId: string,
  body: EnvironmentIdeChangeDirectoryDTO,
): Promise<EnvironmentIdeViewDTO> {
  return request('PUT /api/ide/environments/{environmentId}/views/{viewId}/directory', {
    path: { environmentId, viewId },
    body,
  })
}

/**
 * 目录选择器只列直接子目录；path 省略时服务端返回宿主主目录。
 * 结果中的 path 是服务端规范化后的目录，调用方用它确认切换目标。
 */
export function listIdeDirectories(
  environmentId: string,
  path?: string | null,
  signal?: AbortSignal,
): Promise<EnvironmentIdeDirectoryListingDTO> {
  return request(
    'GET /api/ide/environments/{environmentId}/directories',
    path == null
      ? { path: { environmentId } }
      : { path: { environmentId }, query: { path } },
    { signal },
  )
}

export function focusIdeTerminal(
  environmentId: string,
  viewId: string,
  body: EnvironmentIdeViewBindingDTO,
): Promise<void> {
  return request('POST /api/ide/environments/{environmentId}/views/{viewId}/focus-terminal', {
    path: { environmentId, viewId },
    body,
  })
}

export function applyIdeMobileLayout(
  environmentId: string,
  viewId: string,
  body: EnvironmentIdeViewBindingDTO,
): Promise<void> {
  return request('POST /api/ide/environments/{environmentId}/views/{viewId}/apply-mobile-layout', {
    path: { environmentId, viewId },
    body,
  })
}

/** 普通关闭等待 IDE 内用户操作；仅在状态未知并经用户确认时强制关闭。 */
export function closeIdeView(
  environmentId: string,
  viewId: string,
  body: EnvironmentIdeCloseViewDTO,
): Promise<void> {
  return request(
    'POST /api/ide/environments/{environmentId}/views/{viewId}/close',
    { path: { environmentId, viewId }, body },
    body.discard ? undefined : { timeoutMs: 0 },
  )
}
