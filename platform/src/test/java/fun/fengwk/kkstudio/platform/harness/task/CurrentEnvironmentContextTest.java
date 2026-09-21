package fun.fengwk.kkstudio.platform.harness.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

/** CurrentEnvironmentContext 的不可变契约与宿主事实校验测试。 */
public class CurrentEnvironmentContextTest {

  private static final Instant NOW = Instant.parse("2026-09-13T00:00:00Z");

  // 测试意图: 未选择 Environment 的上下文只允许 Platform 日期，任何宿主事实都必须被拒绝
  @Test
  public void shouldRejectHostFactsWithoutSelectedEnvironment() {
    LocalDate today = LocalDate.of(2026, 9, 13);
    CurrentEnvironmentContext unselected =
        new CurrentEnvironmentContext(null, null, null, null, null, today, null);
    assertNull(unselected.environmentId());
    assertNull(unselected.environmentName());
    assertNull(unselected.operatingSystem());
    assertNull(unselected.userName());
    assertNull(unselected.homeDirectory());
    assertNull(unselected.note());
    assertEquals(today, unselected.currentDate());

    assertThrows(
        IllegalArgumentException.class,
        () -> new CurrentEnvironmentContext(null, "env", null, null, null, today, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CurrentEnvironmentContext(
                null, null, DaemonOperatingSystem.LINUX, null, null, today, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CurrentEnvironmentContext(null, null, null, "dev", null, today, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CurrentEnvironmentContext(null, null, null, null, "/home/dev", today, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CurrentEnvironmentContext(null, null, null, null, null, today, "some-note"));
  }

  // 测试意图: 已选环境必须携带 name，且 userName/homeDirectory/note 复用宿主事实的结构校验
  @Test
  public void shouldValidateSelectedEnvironmentFacts() {
    LocalDate today = LocalDate.of(2026, 9, 13);
    EnvironmentId envId = new EnvironmentId(UUID.randomUUID());
    assertThrows(
        NullPointerException.class,
        () -> new CurrentEnvironmentContext(null, null, null, null, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CurrentEnvironmentContext(envId, null, null, null, null, today, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CurrentEnvironmentContext(envId, "   ", null, null, null, today, null));

    CurrentEnvironmentContext selected =
        new CurrentEnvironmentContext(
            envId,
            "nas-dev",
            DaemonOperatingSystem.MACOS,
            "dev",
            "/Users/dev",
            today,
            "developer machine");
    assertEquals(envId, selected.environmentId());
    assertEquals("nas-dev", selected.environmentName());
    assertEquals(DaemonOperatingSystem.MACOS, selected.operatingSystem());
    assertEquals("dev", selected.userName());
    assertEquals("/Users/dev", selected.homeDirectory());
    assertEquals("developer machine", selected.note());

    // 宿主事实是可选项，缺失时保持 null 而不是伪造默认值。
    CurrentEnvironmentContext nameOnly =
        new CurrentEnvironmentContext(envId, "nas-dev", null, null, null, today, null);
    assertNull(nameOnly.operatingSystem());
    assertNull(nameOnly.userName());
    assertNull(nameOnly.homeDirectory());

    assertThrows(
        IllegalArgumentException.class,
        () -> new CurrentEnvironmentContext(envId, "nas-dev", null, " dev ", null, today, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CurrentEnvironmentContext(
                envId, "nas-dev", null, null, "relative/home", today, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CurrentEnvironmentContext(envId, "nas-dev", null, null, null, today, " "));
  }

  // 测试意图: 未选择 Environment 的工厂只保留 Platform 时钟时区下的当前日期
  @Test
  public void shouldBuildPlatformOnlyDateWhenNoEnvironmentSelected() {
    CurrentEnvironmentContext none =
        CurrentEnvironmentContext.none(NOW, ZoneId.of("America/Los_Angeles"));

    assertEquals(LocalDate.of(2026, 9, 12), none.currentDate());
    assertNull(none.environmentName());
    assertNull(none.operatingSystem());
    assertNull(none.userName());
    assertNull(none.homeDirectory());
    assertNull(none.note());
  }

  // 测试意图: 宿主未报告或报告了不可解析的时区时确定性回退 Platform 时钟时区，绝不 NPE
  @Test
  public void shouldFallBackToPlatformZoneForMissingOrInvalidHostTimeZone() {
    ZoneId fallback = ZoneId.of("UTC");

    assertEquals(fallback, CurrentEnvironmentContext.zoneOf(null, fallback));
    assertEquals(fallback, CurrentEnvironmentContext.zoneOf("   ", fallback));
    assertEquals(fallback, CurrentEnvironmentContext.zoneOf("Not/AZone", fallback));
    assertEquals(ZoneId.of("Asia/Tokyo"), CurrentEnvironmentContext.zoneOf("Asia/Tokyo", fallback));
  }

  // 测试意图: 已选环境的日期按宿主时区换算，宿主从未 READY 时所有可选事实保持 null（绝不伪造）
  @Test
  public void shouldDeriveDateFromHostFactsAndOmitMissingFacts() {
    EnvironmentId envId = new EnvironmentId(UUID.randomUUID());
    CurrentEnvironmentContext unreported =
        CurrentEnvironmentContext.selected(envId, "nas-dev", null, NOW, ZoneOffset.UTC);
    assertEquals(LocalDate.of(2026, 9, 13), unreported.currentDate());
    assertEquals("nas-dev", unreported.environmentName());
    assertNull(unreported.userName());
    assertNull(unreported.homeDirectory());

    DaemonEnvironmentInfo hostFacts =
        new DaemonEnvironmentInfo(
            DaemonOperatingSystem.LINUX,
            "America/Los_Angeles",
            "dev",
            "/home/dev",
            "Linux environment.");
    CurrentEnvironmentContext reported =
        CurrentEnvironmentContext.selected(envId, "nas-dev", hostFacts, NOW, ZoneOffset.UTC);
    assertEquals(LocalDate.of(2026, 9, 12), reported.currentDate());
    assertEquals(DaemonOperatingSystem.LINUX, reported.operatingSystem());
    assertEquals("dev", reported.userName());
    assertEquals("/home/dev", reported.homeDirectory());
    assertEquals("Linux environment.", reported.note());
  }
}
