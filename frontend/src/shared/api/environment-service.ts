import { apiBaseUrl, apiClient, type HttpClient } from '@/shared/api/client'
import type {
  EnvironmentCardDTO,
  EnvironmentCreateDTO,
  EnvironmentEventDTO,
  EnvironmentIdeChangeDirectoryDTO,
  EnvironmentIdeOpenViewDTO,
  EnvironmentIdeViewDTO,
  EnvironmentInstallCodeDTO,
  EnvironmentInstallConfigDTO,
  EnvironmentRuntimeStatusDTO,
  EnvironmentUpdateStartDTO,
} from '@/shared/api/generated/contracts'

export function createEnvironmentService(client: HttpClient = apiClient) {
  return {
    listEnvironments: (): Promise<EnvironmentCardDTO[]> => client.get('/environments'),

    listEnvironmentEvents: (id: string): Promise<EnvironmentEventDTO[]> =>
      client.get(`/environments/${encodeURIComponent(id)}/events`),

    getEnvironment: (id: string): Promise<EnvironmentCardDTO> =>
      client.get(`/environments/${encodeURIComponent(id)}`),

    /**
     * 读取当前 READY 连接代际的运行快照。没有有效 READY owner 时服务端返回
     * {@code environment_unavailable}，调用方必须展示不可用而不是伪造空的 update 状态。
     */
    getEnvironmentRuntime: (id: string): Promise<EnvironmentRuntimeStatusDTO> =>
      client.get(`/environments/${encodeURIComponent(id)}/runtime`),

    /**
     * 请求当前 READY owner 发起受管更新，返回命令接收状态和当前运行快照。
     * {@code SENT_UNCONFIRMED} 不代表 Daemon 已接受或更新已开始，后续进度仍以 runtime 快照为准。
     */
    startEnvironmentUpdate: (id: string): Promise<EnvironmentUpdateStartDTO> =>
      client.post(`/environments/${encodeURIComponent(id)}/update`),

    createEnvironment: (data: EnvironmentCreateDTO): Promise<EnvironmentCardDTO> =>
      client.post('/environments', data),

    /**
     * 签发 5 分钟安装 code。客户端按现有 Result 解包得到 {code, expiresAt}。
     * 签发不轮换 registrationToken；下载安装脚本时服务端签发新凭证，code 随即失效。不自动重试。
     */
    createInstallCode: (id: string, expectedVersion: string): Promise<EnvironmentInstallCodeDTO> =>
      client.post(`/environments/${encodeURIComponent(id)}/install-code`, {
        expectedVersion,
      }),

    saveInstallConfig: (
      id: string,
      expectedVersion: string,
      installConfig: EnvironmentInstallConfigDTO,
    ): Promise<EnvironmentCardDTO> =>
      client.put(`/environments/${encodeURIComponent(id)}/install-config`, {
        expectedVersion,
        installConfig,
      }),

    rotateToken: (id: string, expectedVersion: string): Promise<EnvironmentCardDTO> =>
      client.post(`/environments/${encodeURIComponent(id)}/registration-token`, {
        expectedVersion,
      }),

    deleteEnvironment: (id: string, expectedVersion: string): Promise<void> =>
      client.delete(
        `/environments/${encodeURIComponent(id)}?expectedVersion=${encodeURIComponent(expectedVersion)}`,
      ),

    createIdeView: (environmentId: string, data: EnvironmentIdeOpenViewDTO): Promise<EnvironmentIdeViewDTO> =>
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

    openIdeViewTerminal: (environmentId: string, viewId: string): Promise<void> =>
      client.post(
        `/environments/${encodeURIComponent(environmentId)}/ide/views/${encodeURIComponent(viewId)}/terminal`,
      ),

    closeIdeView: (environmentId: string, viewId: string): Promise<void> =>
      client.delete(ideViewPath(environmentId, viewId)),

    /**
     * 只读探测同源 IDE 会话；网络错误或超时返回 null（未知），不做重试。
     * 探测地址由已校验的 entryUrl 从 __ide/bootstrap 改写为 __ide/session，不带 bootstrap token。
     */
    probeIdeSession: (sessionUrl: string, signal?: AbortSignal): Promise<number | null> =>
      probeIdeSession(sessionUrl, signal),

    /** pagehide 时用 keepalive DELETE 释放视图；Daemon 仍按 dirty 语义拒绝关闭。 */
    closeIdeViewKeepalive: (environmentId: string, viewId: string): Promise<void> =>
      fetch(`${apiBaseUrl}${ideViewPath(environmentId, viewId)}`, {
        method: 'DELETE',
        credentials: 'same-origin',
        keepalive: true,
      }).then(() => undefined),
  }
}

function ideViewPath(environmentId: string, viewId: string): string {
  return `/environments/${encodeURIComponent(environmentId)}/ide/views/${encodeURIComponent(viewId)}`
}

async function probeIdeSession(sessionUrl: string, signal?: AbortSignal): Promise<number | null> {
  try {
    const response = await fetch(sessionUrl, {
      method: 'GET',
      credentials: 'same-origin',
      cache: 'no-store',
      signal,
    })
    return response.status
  } catch {
    return null
  }
}

export const environmentService = createEnvironmentService()
