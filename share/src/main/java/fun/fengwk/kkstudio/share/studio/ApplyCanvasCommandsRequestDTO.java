package fun.fengwk.kkstudio.share.studio;

import lombok.Data;

/**
 * {@code POST /api/canvases/{canvasId}/commands} 的请求体。
 *
 * <p>幂等由服务端强制：每次调用都从 {@link #commandsJson} 重算请求载荷 hash，因此客户端不（也不能）提供 {@code requestHash}。
 */
@Data
public class ApplyCanvasCommandsRequestDTO {
  /** 必填 CAS 期望 revision：非负十进制字符串；与当前 revision 不匹配时返回 409 REVISION_CONFLICT。 */
  private String baseRevision;

  /**
   * 必填客户端幂等键：服务端以 {@code (canvasId, commandId)} 去重（请求 hash 由服务端对 commandsJson 重算）；同 id 不同请求返回 409
   * IDEMPOTENCY_CONFLICT。
   */
  private String commandId;

  /**
   * 必填非空 JSON 数组（UTF-8 原文参与 SHA-256 幂等哈希）；支持命令：create_text_node / create_generate_text_node /
   * create_link / move_nodes / delete_node。
   */
  private String commandsJson;
}
