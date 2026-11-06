import { apiClient, request, type HttpClient } from '@/shared/api/client'
import type {
  EnvironmentCreateDTO,
  EnvironmentDTO,
  EnvironmentEventDTO,
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
  }
}

export const environmentService = createEnvironmentService()
