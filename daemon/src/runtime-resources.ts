import type { ResultContent, ResourceUploader } from './contracts.js'
import {
  createHttpPutObject,
  createResourceTransferClient,
  type PresignedPutSender,
  type ResourceTransferClient,
} from './transport/resources.js'

/**
 * 资源上传控制面。
 *
 * <p>自定义上传器存在时不创建内置客户端。内置客户端的出站仍由会话运行时裁决：只有 READY、已绑定且该调用仍在执行时才发送。
 */
export interface ResourceRuntime {
  readonly uploader: ResourceUploader | null
  readonly client: ResourceTransferClient | null
  onConnectionLost(): void
  onConnectionReady(): void
  onTicket(invocationId: string, payloadJson: string): void
  close(): void
}

export function createResourceRuntime(options: {
  resources?: ResourceUploader
  putObject?: PresignedPutSender
  sendControl(
    messageType: 'RESOURCE_UPLOAD_REQUEST' | 'RESOURCE_UPLOAD_COMMIT',
    invocationId: string,
    payloadJson: string,
  ): boolean
}): ResourceRuntime {
  if (options.resources !== undefined) {
    return {
      uploader: options.resources,
      client: null,
      onConnectionLost(): void {},
      onConnectionReady(): void {},
      onTicket(): void {},
      close(): void {},
    }
  }
  const client = createResourceTransferClient({
    sendControl: options.sendControl,
    putObject: options.putObject ?? createHttpPutObject(),
  })
  return {
    uploader: client,
    client,
    onConnectionLost(): void {
      client.onConnectionLost()
    },
    onConnectionReady(): void {
      client.onConnectionReady()
    },
    onTicket(invocationId, payloadJson): void {
      client.onTicket(invocationId, payloadJson)
    },
    close(): void {
      client.close()
    },
  }
}

/** 供类型收窄：把 ResourceUploader 结果转成 wire 资源内容。 */
export type UploadedResource = Extract<ResultContent, { type: 'resource' }>
