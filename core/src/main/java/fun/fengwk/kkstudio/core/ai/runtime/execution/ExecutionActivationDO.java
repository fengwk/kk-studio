package fun.fengwk.kkstudio.core.ai.runtime.execution;

import lombok.Data;

import java.time.OffsetDateTime;

/** MyBatis 对 harness_execution_activation 一行的可变映射对象。 */
@Data
public class ExecutionActivationDO {

  private String targetKind;
  private Long targetId;
  private String environmentName;
  private String activationState;
  private OffsetDateTime wakeAt;
}
