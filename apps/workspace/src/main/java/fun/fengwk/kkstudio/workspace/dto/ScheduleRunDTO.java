package fun.fengwk.kkstudio.workspace.dto;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record ScheduleRunDTO(
    UUID id,
    Instant scheduledAt,
    @Nullable Instant startedAt,
    String timeZone,
    Result result,
    @Nullable String reason,
    @Nullable ScheduleRunThreadDTO thread) {
  public enum Result {
    SUBMITTED,
    SKIPPED,
    NOT_SUBMITTED
  }
}
