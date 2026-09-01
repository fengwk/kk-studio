package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/**
 * Stop 取消的一条 user-like 消息。
 *
 * <p>{@code messageJson} 是 canonical {@code AgentMessageJsonCodec} JSON；role 固定为 USER，contents 保持原
 * Command 顺序。sequence 按 Thread command sequence 严格递增。
 */
@Data
public class HarnessCancelledUserMessageDTO {

  /** Thread 内命令序号：strict positive decimal string。 */
  private String sequence;

  /** 原命令稳定幂等键：canonical UUID string。 */
  private String idempotencyKey;

  /** 取消消息的 canonical AgentMessage JSON。 */
  private String messageJson;
}
