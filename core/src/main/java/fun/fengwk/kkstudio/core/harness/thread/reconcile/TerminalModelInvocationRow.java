package fun.fengwk.kkstudio.core.harness.thread.reconcile;

import lombok.Data;

/** {@code findTerminalModel} 的终态 ModelInvocation 投影；只包含物化 Assistant Entry 所需字段。 */
@Data
public class TerminalModelInvocationRow {
  private long id;
  private long sourceHeadEntryId;
  private String requestJson;
  private String status;
  private String resultJson;
  private String errorJson;
}
