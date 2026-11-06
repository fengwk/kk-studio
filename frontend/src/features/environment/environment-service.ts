import { apiClient, request, type HttpClient } from '@/shared/api/client'
import type {
  EnvironmentCreateDTO,
  EnvironmentDTO,
  EnvironmentEventDTO,
  EnvironmentIdeChangeDirectoryDTO,
  EnvironmentIdeCloseViewDTO,
  EnvironmentIdeDirectoryListingDTO,
  EnvironmentIdeOpenViewDTO,
  EnvironmentIdeViewBindingDTO,
  EnvironmentIdeViewDTO,
  EnvironmentInstallCodeDTO,
  EnvironmentInstallConfigDTO,
  EnvironmentRuntimeStatusDTO,
  EnvironmentUpdateStartDTO,
} from '@/shared/api/generated/contracts'

export function createEnvironmentService(client: HttpClient = apiClient) {
  return {
    listEnvironments: (): Promise<EnvironmentDTO[]> =>
      request('GET /api/platform/environments', {}),

    listEnvironmentEvents: (id: string): Promise<EnvironmentEventDTO[]> =>
      request('GET /api/platform/environments/{environmentId}/events', {
        path: { environmentId: id },
      }),

    getEnvironment: (id: string): Promise<EnvironmentDTO> =>
      request('GET /api/platform/environments/{environmentId}', { path: { environmentId: id } }),

    /**
     * 读取当前 READY 连接代际的运行快照。没有有效 READY owner 时服务端返回
     * unavailable + ENVIRONMENT_UNAVAILABLE，调用方必须展示不可用而不是伪造空的 update 状态。
     */
    getEnvironmentRuntime: (id: string): Promise<EnvironmentRuntimeStatusDTO> =>
      request('GET /api/platform/environments/{environmentId}/runtime', {
        path: { environmentId: id },
      }),

    /**
     * 请求当前 READY owner 发起受管更新，返回命令接收状态和当前运行快照。
     * {@code SENT_UNCONFIRMED} 不代表 Daemon 已接受或更新已开始，后续进度仍以 runtime 快照为准。
     */
    startEnvironmentUpdate: (id: string): Promise<EnvironmentUpdateStartDTO> =>
      request('POST /api/platform/environments/{environmentId}/update-daemon', {
        path: { environmentId: id },
      }),

    createEnvironment: (data: EnvironmentCreateDTO): Promise<EnvironmentDTO> =>
      request('POST /api/platform/environments', { body: data }),

    /**
     * 签发 5 分钟安装 code。签发不轮换 registrationToken；下载安装脚本时服务端签发新凭证，code 随即失效。不自动重试。
     */
    createInstallCode: (id: string, expectedVersion: string): Promise<EnvironmentInstallCodeDTO> =>
      request('POST /api/platform/environments/{environmentId}/install-codes', {
        path: { environmentId: id },
        body: { expectedVersion },
      }),

    saveInstallConfig: (
      id: string,
      expectedVersion: string,
      installConfig: EnvironmentInstallConfigDTO,
    ): Promise<EnvironmentDTO> =>
      request('PUT /api/platform/environments/{environmentId}/install-settings', {
        path: { environmentId: id },
        body: { expectedVersion, installConfig },
      }),

    rotateToken: (id: string, expectedVersion: string): Promise<EnvironmentDTO> =>
      request('POST /api/platform/environments/{environmentId}/rotate-token', {
        path: { environmentId: id },
        body: { expectedVersion },
      }),

    deleteEnvironment: (id: string, expectedVersion: string): Promise<void> =>
      request('DELETE /api/platform/environments/{environmentId}', {
        path: { environmentId: id },
        query: { expectedVersion },
      }),

    /** 打开或恢复 view：claimId 与 mode 由调用方持有，失败后只允许按原 claimId 对账。 */
    openIdeView: (environmentId: string, data: EnvironmentIdeOpenViewDTO): Promise<EnvironmentIdeViewDTO> =>
      client.post(`/environments/${encodeURIComponent(environmentId)}/ide/views`, data),

    changeIdeViewDirectory: (
      environmentId: string,
      viewId: string,
      data: EnvironmentIdeChangeDirectoryDTO,
    ): Promise<EnvironmentIdeViewDTO> =>
      client.put(
        `/environments/${encodeURIComponent(environmentId)}/ide/views/${encodeURIComponent(viewId)}/directory`,
        data,
      ),

    /**
     * 目录选择器只列直接子目录；path 省略时服务端返回宿主主目录。
     * 结果中的 path 是服务端规范化后的目录，调用方用它确认切换目标。
     */
    listIdeDirectories: (
      environmentId: string,
      path?: string | null,
      signal?: AbortSignal,
    ): Promise<EnvironmentIdeDirectoryListingDTO> =>
      client.get(
        `/environments/${encodeURIComponent(environmentId)}/ide/directories`,
        path == null ? { signal } : { params: { path }, signal },
      ),

    openIdeViewTerminal: (
      environmentId: string,
      viewId: string,
      data: EnvironmentIdeViewBindingDTO,
    ): Promise<void> =>
      client.post(
        `/environments/${encodeURIComponent(environmentId)}/ide/views/${encodeURIComponent(viewId)}/terminal`,
        data,
      ),

    setIdeViewMobileLayout: (
      environmentId: string,
      viewId: string,
      data: EnvironmentIdeViewBindingDTO,
    ): Promise<void> =>
      client.post(`${ideViewPath(environmentId, viewId)}/mobile-layout`, data),

    /** 普通关闭等待 IDE 内用户操作；仅在状态未知并经用户确认时强制关闭。 */
    closeIdeView: (
      environmentId: string,
      viewId: string,
      data: EnvironmentIdeCloseViewDTO,
    ): Promise<void> =>
      client.delete(ideViewPath(environmentId, viewId), data.discard ? { data } : { data, timeout: 0 }),
  }
}

function ideViewPath(environmentId: string, viewId: string): string {
  return `/environments/${encodeURIComponent(environmentId)}/ide/views/${encodeURIComponent(viewId)}`
}

export const environmentService = createEnvironmentService()
