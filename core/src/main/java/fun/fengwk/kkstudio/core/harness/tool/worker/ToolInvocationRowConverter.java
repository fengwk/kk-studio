package fun.fengwk.kkstudio.core.harness.tool.worker;

import fun.fengwk.kkstudio.harness.runtime.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.execution.Lease;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolPermissionState;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationErrorJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolResultJsonCodec;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Strict conversion between final PostgreSQL rows and Runtime ToolInvocation aggregates. */
final class ToolInvocationRowConverter {

  private static final ToolDescriptorJsonCodec DESCRIPTOR_CODEC = new ToolDescriptorJsonCodec();
  private static final ToolInvocationErrorJsonCodec ERROR_CODEC =
      new ToolInvocationErrorJsonCodec();

  private ToolInvocationRowConverter() {}

  static ToolInvocation toAggregate(ToolInvocationDO row) {
    Objects.requireNonNull(row, "row");
    if (row.getPermissionState() == null) {
      throw new IllegalStateException(
          "tool invocation row " + row.getId() + " is missing permission_state");
    }
    if (row.getYoloEnabled() == null) {
      throw new IllegalStateException(
          "tool invocation row " + row.getId() + " is missing yolo_enabled");
    }
    Lease lease =
        row.getWorkerToken() == null
            ? null
            : new Lease(
                row.getWorkerToken(), Objects.requireNonNull(row.getWorkerUntil()).toInstant());
    ToolDescriptor descriptor = DESCRIPTOR_CODEC.decode(row.getDescriptorJson());
    ToolResult result =
        row.getResultJson() == null ? null : ToolResultJsonCodec.decode(row.getResultJson());
    ToolInvocationError error =
        row.getErrorJson() == null ? null : ERROR_CODEC.decode(row.getErrorJson());
    ToolPermissionState permissionState = ToolPermissionState.fromValue(row.getPermissionState());
    boolean yoloEnabled = row.getYoloEnabled();
    return new ToolInvocation(
        row.getId(),
        row.getThreadId(),
        row.getAssistantEntryId(),
        row.getOrdinal(),
        row.getToolCallId(),
        descriptor,
        row.getArgumentsJson(),
        ToolExecutionLocation.valueOf(row.getLocation()),
        row.getEnvironmentName(),
        row.getExecutionEpoch(),
        InvocationStatus.valueOf(row.getStatus()),
        row.getAttempt(),
        instant(row.getNextAttemptAt()),
        lease,
        instant(row.getDeadlineAt()),
        instant(row.getLastActivityAt()),
        result,
        error,
        instant(row.getAppliedAt()),
        Objects.requireNonNull(row.getCreatedAt(), "createdAt").toInstant(),
        instant(row.getStartedAt()),
        instant(row.getFinishedAt()),
        permissionState,
        yoloEnabled);
  }

  static OffsetDateTime offset(Instant value) {
    return OffsetDateTime.ofInstant(persistenceInstant(value), ZoneOffset.UTC);
  }

  static Instant persistenceInstant(Instant value) {
    return Objects.requireNonNull(value, "value").truncatedTo(ChronoUnit.MILLIS);
  }

  static String encodeResult(ToolResult result) {
    return ToolResultJsonCodec.encode(Objects.requireNonNull(result, "result"));
  }

  static String encodeError(ToolInvocationError error) {
    return ERROR_CODEC.encode(Objects.requireNonNull(error, "error"));
  }

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
