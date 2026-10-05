package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 一条执行树节点的最小投影。
 *
 * <p>只暴露父子关系、名称、当前模型、运行状态和当前 head 路径上的累计计数。不包含 Entry、Command、Tool 参数或结果。 {@code parentThreadId} 与
 * {@code outcome} 必须显式输出 null，避免根节点和未结束节点被当成字段缺失。
 */
@Data
public class HarnessThreadTreeNodeDTO {
  /** Thread 主键：canonical UUID string。 */
  private String threadId;

  /** 不可变执行父 Thread；根节点为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String parentThreadId;

  /** 服务端权威展示名。 */
  private String name;

  /** 当前 head 路径生效的 agent 名。 */
  private String agentName;

  /** 当前 head 路径生效的模型选择。 */
  private HarnessModelSelectionDTO model;

  /** Runtime 的细分状态名称。 */
  private String status;

  /** {@code status != IDLE}。 */
  private boolean processing;

  /** 当前 root-to-head 的模型工作轮数，不含 COMPACTION 与 STOP。 */
  private int turnCount;

  /** 当前 root-to-head 上 ASSISTANT MESSAGE 的 ToolCall 数量。 */
  private int toolCallCount;

  /** 仅 IDLE 且当前 head 为 TURN_END 时的 outcome；其它为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String outcome;
}
