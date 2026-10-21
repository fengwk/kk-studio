package fun.fengwk.kkstudio.harness.provider.transport;

/** Transport 层失败原因分类。 */
public enum TransportErrorKind {
  /** 底层网络连接中断或 I/O 读写失败。 */
  IO,

  /** 总模型调用时长或无活动闲置（idle）超时。 */
  TIMEOUT,

  /** 响应协议非法（例如非 2xx/非 text/event-stream、畸形 UTF-8、NUL 字节、超长行/事件/正文）。 */
  INVALID_RESPONSE,

  /** 服务端返回非 2xx 状态码（含 3xx 重定向或 4xx/5xx）。 */
  HTTP_STATUS,

  /** 调用线程在请求或读取期间被中断（取消）。 */
  CANCELLED
}
