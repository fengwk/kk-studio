import { apiClient, type HttpClient } from '@/shared/api/client'
import { decodeInteractionPage } from '@/shared/api/ai-interaction-codec'
import type {
  HarnessToolInputDTO,
  HarnessToolInputResultDTO,
  InteractionPageDTO,
} from '@/shared/api/contracts/ai-interaction'

export class InteractionService {
  private readonly client: HttpClient

  constructor(client: HttpClient = apiClient) {
    this.client = client
  }

  /**
   * 查询一页待处理 Interaction（人工等待与环境等待合并）。
   * 传入 rootThreadId 时只返回归属该执行根的待办；分页语义与不传时一致。
   * 响应经严格 wire codec 解码，非法载荷 fail closed，不做静默降级。
   */
  async listInteractions(
    rootThreadId?: string | null,
    cursor?: string | null,
    limit?: number | null,
  ): Promise<InteractionPageDTO> {
    const params: Record<string, unknown> = {}
    if (rootThreadId) {
      params.rootThreadId = rootThreadId
    }
    if (cursor) {
      params.cursor = cursor
    }
    if (limit != null) {
      params.limit = limit
    }
    const payload = await this.client.get<unknown>('/interactions', { params })
    return decodeInteractionPage(payload)
  }

  /**
   * 提交一次人工问卷回答（或明确拒答）。
   * 请求体严禁携带 actor 字段（服务端从认证主体自动解析）。
   */
  async submitToolInput(
    interactionId: string,
    body: HarnessToolInputDTO,
  ): Promise<HarnessToolInputResultDTO> {
    return this.client.post<HarnessToolInputResultDTO>(
      `/interactions/${encodeURIComponent(interactionId)}/input`,
      body,
    )
  }
}

export const interactionService = new InteractionService()
