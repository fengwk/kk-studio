package fun.fengwk.kkstudio.core.harness.thread.reconcile;

import lombok.Data;

import java.time.OffsetDateTime;

/** {@code listToolSiblings} 的 ToolInvocation 投影；用于检查 sibling 批次并物化 Tool result。 */
@Data
public class ToolInvocationRow {
  private String toolCallId;
  private String descriptorJson;
  private OffsetDateTime appliedAt;
  private String resultJson;
  private String errorJson;
  private String status;
}
