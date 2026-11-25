package fun.fengwk.kkstudio.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.workspace.dto.ScheduleTriggerDTO;
import fun.fengwk.kkstudio.workspace.dto.ScheduleTriggerDTO.Frequency;
import fun.fengwk.kkstudio.workspace.dto.ScheduleTriggerDTO.Type;

import java.time.Instant;
import java.util.List;

class ScheduleTimingTest {
  @Test
  void skipsSpringGapAndUsesFirstFallOverlap() {
    ScheduleTriggerDTO trigger =
        new ScheduleTriggerDTO(Type.REPEAT, Frequency.DAILY, null, "02:30", null, null, null);
    List<Instant> spring =
        ScheduleTiming.next(trigger, "Europe/Paris", Instant.parse("2024-03-30T02:00:00Z"), 2);
    assertEquals(
        List.of(Instant.parse("2024-04-01T00:30:00Z"), Instant.parse("2024-04-02T00:30:00Z")),
        spring);

    List<Instant> fall =
        ScheduleTiming.next(trigger, "Europe/Paris", Instant.parse("2024-10-26T02:00:00Z"), 2);
    assertEquals(
        List.of(Instant.parse("2024-10-27T00:30:00Z"), Instant.parse("2024-10-28T01:30:00Z")),
        fall);
  }

  @Test
  void onlyCatchesUpTheLatestOccurrenceInTheGraceWindow() {
    var trigger =
        new ScheduleTriggerDTO(Type.REPEAT, Frequency.CRON, null, null, null, null, "*/5 * * * *");
    var now = Instant.parse("2024-01-01T10:09:00Z");
    assertEquals(
        Instant.parse("2024-01-01T10:05:00Z"),
        ScheduleTiming.previous(trigger, "UTC", now).orElseThrow());

    var once = new ScheduleTriggerDTO(Type.ONCE, null, "2024-01-01", "09:59", null, null, null);
    assertEquals(
        Instant.parse("2024-01-01T09:59:00Z"),
        ScheduleTiming.previous(once, "UTC", now).orElseThrow());
    assertEquals(true, ScheduleTiming.previous(once, "UTC", now.plusMillis(1)).isEmpty());
  }

  @Test
  void rejectsFieldsThatDoNotBelongToTheTrigger() {
    var bad = new ScheduleTriggerDTO(Type.REPEAT, Frequency.HOURLY, null, "08:00", null, 0, null);
    assertThrows(
        IllegalArgumentException.class, () -> ScheduleTiming.next(bad, "UTC", Instant.EPOCH, 3));
  }
}
