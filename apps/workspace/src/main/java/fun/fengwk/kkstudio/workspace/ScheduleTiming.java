package fun.fengwk.kkstudio.workspace;

import org.springframework.scheduling.support.CronExpression;

import fun.fengwk.kkstudio.workspace.dto.ScheduleTriggerDTO;
import fun.fengwk.kkstudio.workspace.dto.ScheduleTriggerDTO.Frequency;
import fun.fengwk.kkstudio.workspace.dto.ScheduleTriggerDTO.Type;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class ScheduleTiming {
  private ScheduleTiming() {}

  /** Enumerate local minutes first: gaps have no instant, overlaps use the earlier instant. */
  static List<Instant> next(ScheduleTriggerDTO trigger, String zone, Instant after, int limit) {
    require(
        trigger != null
            && trigger.type() != null
            && zone != null
            && ZoneId.getAvailableZoneIds().contains(zone));
    ZoneId zoneId = ZoneId.of(zone);
    if (trigger.type() == Type.ONCE) {
      require(
          trigger.localDate() != null
              && trigger.frequency() == null
              && trigger.weekday() == null
              && trigger.minute() == null
              && trigger.cron() == null);
      return instant(LocalDate.parse(trigger.localDate()).atTime(time(trigger.time())), zoneId)
          .filter(value -> value.isAfter(after))
          .stream()
          .limit(limit)
          .toList();
    }
    CronExpression cron = repeat(trigger);
    List<Instant> result = new ArrayList<>();
    LocalDateTime cursor = LocalDateTime.ofInstant(after, zoneId);
    while (result.size() < limit && (cursor = cron.next(cursor)) != null) {
      instant(cursor, zoneId).filter(value -> value.isAfter(after)).ifPresent(result::add);
    }
    return List.copyOf(result);
  }

  static Optional<Instant> previous(ScheduleTriggerDTO trigger, String zone, Instant at) {
    return next(trigger, zone, at.minus(Duration.ofMinutes(10)).minusNanos(1), 11).stream()
        .filter(value -> !value.isAfter(at))
        .reduce((a, b) -> b);
  }

  private static Optional<Instant> instant(LocalDateTime local, ZoneId zone) {
    return zone.getRules().getValidOffsets(local).stream().findFirst().map(local::toInstant);
  }

  private static CronExpression repeat(ScheduleTriggerDTO t) {
    Frequency f = t.frequency();
    require(
        f != null
            && t.localDate() == null
            && (t.time() != null) == (f != Frequency.HOURLY && f != Frequency.CRON)
            && (t.weekday() != null) == (f == Frequency.WEEKLY)
            && (t.minute() != null) == (f == Frequency.HOURLY)
            && (t.cron() != null) == (f == Frequency.CRON));
    if (f == Frequency.CRON) {
      String expression = t.cron().strip();
      require(expression.split("\\s+").length == 5);
      return CronExpression.parse("0 " + expression);
    }
    if (f == Frequency.HOURLY) {
      return CronExpression.parse("0 " + t.minute() + " * * * *");
    }
    LocalTime time = time(t.time());
    String day =
        switch (f) {
          case WEEKLY -> Integer.toString(DayOfWeek.valueOf(t.weekday()).getValue() % 7);
          case WEEKDAYS -> "MON-FRI";
          default -> "*";
        };
    return CronExpression.parse("0 " + time.getMinute() + " " + time.getHour() + " * * " + day);
  }

  private static LocalTime time(String text) {
    require(text != null && text.matches("[0-9]{2}:[0-9]{2}"));
    return LocalTime.parse(text);
  }

  private static void require(boolean condition) {
    if (!condition) {
      throw new IllegalArgumentException("Invalid schedule trigger or time zone");
    }
  }
}
