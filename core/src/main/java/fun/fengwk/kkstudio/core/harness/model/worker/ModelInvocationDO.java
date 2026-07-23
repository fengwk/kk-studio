package fun.fengwk.kkstudio.core.harness.model.worker;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * {@code harness_model_invocation} 行映射。时间列均为 {@code timestamptz(3)}；adapter 在写入前统一归一到毫秒精度，DO 使用
 * {@link OffsetDateTime} 保留 PostgreSQL 返回的时区语义。jsonb 列以字符串形式持有，由 MyBatis 通过 {@code cast(? as
 * jsonb)} 写库。
 */
@Data
public class ModelInvocationDO {

  private Long id;
  private Long threadId;
  private Long sessionId;
  private Long sourceHeadEntryId;
  private Long executionEpoch;
  private String requestJson;
  private String status;
  private Integer attempt;

  private OffsetDateTime nextAttemptAt;
  private String workerToken;
  private OffsetDateTime workerUntil;

  private OffsetDateTime deadlineAt;
  private OffsetDateTime lastActivityAt;

  private String resultJson;
  private String errorJson;

  private OffsetDateTime appliedAt;
  private OffsetDateTime createdAt;
  private OffsetDateTime startedAt;
  private OffsetDateTime finishedAt;
}
