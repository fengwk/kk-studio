package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/**
 * Stop 取消的一条人工输入。
 *
 * <p>只包含 HUMAN USER_MESSAGE / GOAL：{@code CUSTOM_MESSAGE} 与 NOTIFICATION 不是用户草稿，不在此返回。{@code
 * payloadJson} 是 canonical {@code ThreadCommandPayload} JSON，随命令类型变化；{@code type} 是命令类型名。sequence 按
 * Thread command sequence 严格递增。
 */
@Data
public class HarnessCancelledInputDTO {

  /** Thread 内命令序号：strict positive decimal string。 */
  private String sequence;

  /** 原命令稳定幂等键：canonical UUID string。 */
  private String idempotencyKey;

  /** 命令类型名：USER_MESSAGE 或 GOAL。 */
  private String type;

  /** 原命令载荷的 canonical ThreadCommandPayload JSON。 */
  private String payloadJson;
}
