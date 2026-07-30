package fun.fengwk.kkstudio.core.ai.runtime.execution;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * MyBatis row model for {@code harness_execution_target}. Mapping through a settable DO avoids
 * brittle constructor-injection on the immutable {@link ExecutionTargetRow}; the store converts DO
 * to Row before handing the projection to domain code.
 */
@Data
public class ExecutionTargetDO {

  private String targetKind;
  private Long targetId;
  private String routeKey;
  private boolean dispatchEnabled;
  private OffsetDateTime availableAt;
}
