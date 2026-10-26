package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

import java.time.Instant;

/** 一个具体执行代次尚未确认退出的最小投影。 */
@Data
public class HarnessExecutionExitDTO {

  /** 具体执行代次：canonical UUID string。 */
  private String executionId;

  /** 关联的逻辑 Model 或 Tool invocation：canonical UUID string。 */
  private String invocationId;

  /** 该 invocation 的具体执行代次，从 1 开始。 */
  private int attempt;

  /** {@code MODEL}、{@code TOOL} 或 {@code REMOTE}。 */
  private String kind;

  /** 登记时间（UTC Instant）。 */
  private Instant registeredAt;
}
