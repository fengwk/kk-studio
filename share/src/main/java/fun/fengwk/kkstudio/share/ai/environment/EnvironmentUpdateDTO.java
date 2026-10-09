package fun.fengwk.kkstudio.share.ai.environment;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;

/**
 * 一次受管 Daemon 更新的持久投影：目标版本、阶段、可选失败说明与时间戳。
 *
 * <p>{@code phase} 的持久取值是
 * PENDING/RUNNING/PREPARED/SUCCEEDED/FAILED；当操作仍在推进（PENDING/RUNNING/PREPARED）而宿主当前不可达时， 读取派生出
 * {@code UNKNOWN} 表示「等待重连确认」，它不是一个被持久化的阶段，也不代表失败。
 */
@Data
public class EnvironmentUpdateDTO {
  private String operationId;
  private String targetVersion;
  private String phase;

  /** 仅失败时返回；有界、去敏。 */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private String error;

  private Instant createdAt;
  private Instant updatedAt;
}
