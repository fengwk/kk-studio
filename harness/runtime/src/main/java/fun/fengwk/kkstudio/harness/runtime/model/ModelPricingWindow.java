package fun.fengwk.kkstudio.harness.runtime.model;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;

/** 每天重复的价格时段：在 {@code zone} 的本地时间 {@code [start, end)} 内生效；{@code end} 早于 {@code start} 表示跨越午夜。 */
public record ModelPricingWindow(ZoneId zone, LocalTime start, LocalTime end) {

  public ModelPricingWindow {
    Objects.requireNonNull(zone, "zone");
    Objects.requireNonNull(start, "start");
    Objects.requireNonNull(end, "end");
    if (start.equals(end)) {
      throw new IllegalArgumentException("pricing window start and end must differ");
    }
  }

  /** {@code at} 是否落在该时段内。 */
  public boolean contains(Instant at) {
    LocalTime local = at.atZone(zone).toLocalTime();
    return start.isBefore(end)
        ? !local.isBefore(start) && local.isBefore(end)
        : !local.isBefore(start) || local.isBefore(end);
  }
}
