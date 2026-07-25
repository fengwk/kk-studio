package fun.fengwk.kkstudio.core.harness.model.worker;

import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.codec.ProviderRequestJsonCodec;
import fun.fengwk.kkstudio.harness.model.provider.codec.ProviderResponseJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.execution.Lease;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationErrorJsonCodec;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 在 {@link ModelInvocationDO} / {@link ModelInvocation} 之间做严格转换：所有时间字段以 UTC {@link OffsetDateTime}
 * 在数据库层交互，聚合层使用 {@link Instant}；jsonb 通过 strict json codec 编解码，避免 wire 字符串悄悄引入未校验字段。
 */
final class ModelInvocationRowConverter {

  private static final ProviderRequestJsonCodec REQUEST_CODEC = new ProviderRequestJsonCodec();
  private static final ProviderResponseJsonCodec RESPONSE_CODEC = new ProviderResponseJsonCodec();
  private static final ModelInvocationErrorJsonCodec ERROR_CODEC =
      new ModelInvocationErrorJsonCodec();

  private ModelInvocationRowConverter() {}

  /** 把 DO 还原为 Runtime aggregate；任何解码失败都视为 invariant breach。 */
  static ModelInvocation toAggregate(ModelInvocationDO row) {
    Objects.requireNonNull(row, "row");
    InvocationStatus status = InvocationStatus.valueOf(row.getStatus());
    Lease lease = null;
    if (row.getWorkerToken() != null) {
      lease =
          new Lease(row.getWorkerToken(), Objects.requireNonNull(row.getWorkerUntil()).toInstant());
    }
    ProviderRequest request = REQUEST_CODEC.decode(row.getRequestJson());
    ProviderResponse result =
        row.getResultJson() == null ? null : RESPONSE_CODEC.decode(row.getResultJson());
    ModelInvocationError error =
        row.getErrorJson() == null ? null : ERROR_CODEC.decode(row.getErrorJson());
    return new ModelInvocation(
        row.getId(),
        row.getThreadId(),
        row.getSourceHeadEntryId(),
        row.getExecutionEpoch(),
        request,
        status,
        row.getAttempt(),
        instantOrNull(row.getNextAttemptAt()),
        lease,
        instantOrNull(row.getDeadlineAt()),
        instantOrNull(row.getLastActivityAt()),
        result,
        error,
        instantOrNull(row.getAppliedAt()),
        Objects.requireNonNull(row.getCreatedAt(), "createdAt").toInstant(),
        instantOrNull(row.getStartedAt()),
        instantOrNull(row.getFinishedAt()));
  }

  static OffsetDateTime toUtcOffsetDateTime(Instant instant) {
    Objects.requireNonNull(instant, "instant");
    return OffsetDateTime.ofInstant(toPersistenceInstant(instant), ZoneOffset.UTC);
  }

  /** Normalizes application timestamps to the authoritative {@code timestamptz(3)} precision. */
  static Instant toPersistenceInstant(Instant instant) {
    return Objects.requireNonNull(instant, "instant").truncatedTo(ChronoUnit.MILLIS);
  }

  static Instant toInstant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  static String encodeRequest(ProviderRequest request) {
    return REQUEST_CODEC.encode(Objects.requireNonNull(request, "request"));
  }

  static String encodeResponse(ProviderResponse response) {
    return RESPONSE_CODEC.encode(Objects.requireNonNull(response, "response"));
  }

  static String encodeError(ModelInvocationError error) {
    return ERROR_CODEC.encode(Objects.requireNonNull(error, "error"));
  }

  private static Instant instantOrNull(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
