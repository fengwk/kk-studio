package fun.fengwk.kkstudio.workspace.dto;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record ScheduleDTO(
    UUID id,
    String title,
    String message,
    boolean enabled,
    ScheduleTriggerDTO trigger,
    String timeZone,
    ScheduleTargetDTO target,
    @Nullable Instant nextDueAt,
    long version) {}
