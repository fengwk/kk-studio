package fun.fengwk.kkstudio.core.harness.thread.store.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** Thread 列表投影：附加 Session 标题。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class HarnessThreadViewDO extends HarnessThreadDO {
  /** 所属 Session 标题。 */
  private String sessionTitle;
}
