package fun.fengwk.kkstudio.workspace.dto;

import org.jspecify.annotations.Nullable;

public record ScheduleTriggerDTO(
    Type type,
    @Nullable Frequency frequency,
    @Nullable String localDate,
    @Nullable String time,
    @Nullable String weekday,
    @Nullable Integer minute,
    @Nullable String cron) {
  public enum Type {
    REPEAT,
    ONCE
  }

  public enum Frequency {
    DAILY,
    WEEKDAYS,
    WEEKLY,
    HOURLY,
    CRON
  }
}
