import { request } from '@/shared/api/client'
import type {
  EnvironmentCreateDTO,
  EnvironmentDTO,
  EnvironmentEventDTO,
  EnvironmentInstallCodeDTO,
  EnvironmentRuntimeStatusDTO,
  EnvironmentUpdateDTO,
  EnvironmentUpdateStartDTO,
} from '@/shared/api/generated/contracts'

export function createEnvironmentService() {
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
     * 读取当前有效 owner 的运行快照。CONNECTING 但租约仍有效时也允许读取；
     * 没有有效 owner 时服务端返回 unavailable + ENVIRONMENT_UNAVAILABLE。
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

    /** 创建完整卡片：name、note 与 daemonConfig 一次提交；name 创建后不可变。 */
    createEnvironment: (data: EnvironmentCreateDTO): Promise<EnvironmentDTO> =>
      request('POST /api/platform/environments', { body: data }),

    /** 整体替换可编辑属性；name 不属于更新请求，expectedVersion 参与 CAS。 */
    updateEnvironment: (id: string, data: EnvironmentUpdateDTO): Promise<EnvironmentDTO> =>
      request('PUT /api/platform/environments/{environmentId}', {
        path: { environmentId: id },
        body: data,
      }),

    /**
     * 签发 5 分钟安装 code。签发不轮换 registrationToken；下载安装脚本时服务端签发新凭证，code 随即失效。不自动重试。
     */
    createInstallCode: (id: string, expectedVersion: string): Promise<EnvironmentInstallCodeDTO> =>
      request('POST /api/platform/environments/{environmentId}/install-codes', {
        path: { environmentId: id },
        body: { expectedVersion },
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
