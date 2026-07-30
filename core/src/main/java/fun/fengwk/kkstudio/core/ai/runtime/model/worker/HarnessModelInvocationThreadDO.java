package fun.fengwk.kkstudio.core.ai.runtime.model.worker;

import lombok.Data;

/** 适配器视角的 {@code harness_thread} 行视图，只覆盖 ownership 与 runnable 所需字段。 */
@Data
public class HarnessModelInvocationThreadDO {

  private Long id;
  private Boolean runnable;
  private Long executionEpoch;
}
