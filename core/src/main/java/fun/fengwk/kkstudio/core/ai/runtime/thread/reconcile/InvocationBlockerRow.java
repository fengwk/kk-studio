package fun.fengwk.kkstudio.core.ai.runtime.thread.reconcile;

import lombok.Data;

/** Model/Tool blocker 查询的最小投影；Reconciler 只需要未终态 Invocation 的 id。 */
@Data
public class InvocationBlockerRow {
  private long id;
}
