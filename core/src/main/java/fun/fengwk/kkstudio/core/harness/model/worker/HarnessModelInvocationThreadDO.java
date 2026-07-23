package fun.fengwk.kkstudio.core.harness.model.worker;

import lombok.Data;

/**
 * 适配器视角的 {@code harness_thread} 行视图，只覆盖 ownership 与 runnable 所需字段，避免复用绑定 legacy 列的 {@code
 * HarnessThreadMapper}。
 */
@Data
public class HarnessModelInvocationThreadDO {

  private Long id;
  private Boolean runnable;
  private Long executionEpoch;
}
