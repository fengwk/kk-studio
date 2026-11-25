package fun.fengwk.kkstudio.workspace.dto;

import org.jspecify.annotations.Nullable;

public record ScheduleDraftDTO(
    String title,
    String message,
    @Nullable Boolean enabled,
    ScheduleTriggerDTO trigger,
    String timeZone,
    ScheduleTargetDTO target) {}
