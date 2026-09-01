package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;

/**
 * durable Thread mailbox 命令投影。
 *
 * <p>身份为 {@code (threadId, sequence)}，无代理主键；{@code state} 由 durable
 * 终态标记派生（QUEUED/APPLIED/CANCELLED）； {@code payloadJson} 是类型化命令载荷的 canonical JSON。
 */
@Data
public class HarnessThreadCommandDTO {
  /** 所属 Thread 主键：canonical UUID string。 */
  private String threadId;

  /** Thread 内单调递增的命令序号：strict positive decimal string（从 1 开始）。 */
  private String sequence;

  /**
   * 命令类型，取 {@code ThreadCommandType} 枚举名：USER_MESSAGE / CUSTOM_MESSAGE / SET_AGENT / SET_MODEL /
   * SET_ENVIRONMENT。
   */
  private String type;

  /** 派生生命周期状态，取 {@code ThreadCommandState} 枚举名：QUEUED / APPLIED / CANCELLED。 */
  private String state;

  /** 稳定客户端幂等键（创建时提供）：canonical UUID string。 */
  private String idempotencyKey;

  /** 命令载荷的 canonical JSON（ThreadCommandPayloadJsonCodec 编码），内容形态随 type 变化。 */
  private String payloadJson;

  /** 取消时间（UTC Instant）：仅 CANCELLED 状态非 null（{@code @JsonInclude(ALWAYS)}）。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private Instant cancelledAt;

  /** 命令入队时间（UTC Instant）。 */
  private Instant createTime;
}
