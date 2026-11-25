package fun.fengwk.kkstudio.workspace.dto;

import org.jspecify.annotations.Nullable;

public record SchedulePatchDTO(
    String expectedVersion,
    @Nullable String title,
    @Nullable String message,
    @Nullable Boolean enabled,
    @Nullable ScheduleTriggerDTO trigger,
    @Nullable String timeZone,
    @Nullable ScheduleTargetDTO target) {}
