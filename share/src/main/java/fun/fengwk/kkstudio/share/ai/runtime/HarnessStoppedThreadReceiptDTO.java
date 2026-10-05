package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * 一次 Stop 在某个受影响 Thread 上留下的持久回执。
 *
 * <p>回执身份是 {@code (threadId, stopRequestId)}：同一 stop 请求精确重放返回同一回执，不按当前树重算旧范围。{@code
 * cancelledCommandCount} 是该 Thread 本次取消的命令总数（含不退草稿的 CUSTOM_MESSAGE / NOTIFICATION 与配置），{@code
 * cancelledInputs} 只含可恢复的人工输入。
 */
@Data
public class HarnessStoppedThreadReceiptDTO {

  /** 受影响 Thread 主键：canonical UUID string。 */
  private String threadId;

  /** 该 Thread 上的稳定 stop 请求幂等键：canonical UUID string。 */
  private String stopRequestId;

  /**
   * 该 Thread 自己停止边界 TURN_END 的主键：canonical UUID string；没有可写的停止边界时为
   * null（{@code @JsonInclude(ALWAYS)}）。
   */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String stoppedTurnEndEntryId;

  /** 该 Thread 本次取消的命令总数（非负整数）。 */
  private Integer cancelledCommandCount;

  /** 该 Thread 被取消的人工 USER_MESSAGE / GOAL，按 sequence 升序。 */
  private List<HarnessCancelledInputDTO> cancelledInputs = List.of();
}
