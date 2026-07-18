package fun.fengwk.kkstudio.core.harness.thread.store.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** Thread 列表/详情 join 投影：在 {@link HarnessThreadDO} 上附带 session 标题，避免 N+1。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class HarnessThreadViewDO extends HarnessThreadDO {
  /** 所属 Session 标题。 */
  private String sessionTitle;
}
