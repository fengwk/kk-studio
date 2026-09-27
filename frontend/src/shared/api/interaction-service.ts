import { apiClient, type HttpClient } from '@/shared/api/client'
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
   * 查询一页待处理 Interaction（问卷等待与审批等待合并）。
   */
  async listInteractions(
    cursor?: string | null,
    limit?: number | null,
  ): Promise<InteractionPageDTO> {
    const params: Record<string, unknown> = {}
    if (cursor) {
      params.cursor = cursor
    }
    if (limit != null) {
      params.limit = limit
    }
    return this.client.get<InteractionPageDTO>('/interactions', { params })
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
