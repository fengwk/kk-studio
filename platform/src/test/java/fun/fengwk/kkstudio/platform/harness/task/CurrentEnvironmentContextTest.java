package fun.fengwk.kkstudio.platform.harness.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;

import java.time.LocalDate;
import java.util.UUID;

/** CurrentEnvironmentContext 的不可变契约与元数据校验测试。 */
public class CurrentEnvironmentContextTest {

  // 测试意图: 验证未选择环境上下文在未携带元数据时可以正常构造，携带元数据时必须拒绝
  @Test
  public void shouldEnforceUnselectedEnvironmentRules() {
    LocalDate now = LocalDate.of(2026, 9, 13);
    CurrentEnvironmentContext unselected = new CurrentEnvironmentContext(null, null, now, null);
    assertNull(unselected.environmentId());
    assertNull(unselected.operatingSystem());
    assertNull(unselected.note());
    assertEquals(now, unselected.currentDate());

    assertThrows(
        IllegalArgumentException.class,
        () -> new CurrentEnvironmentContext(null, DaemonOperatingSystem.LINUX, now, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CurrentEnvironmentContext(null, null, now, "some-note"));
  }

  // 测试意图: 验证必填字段 currentDate 不能为 null，且已选环境可独立携带 OS 与 note
  @Test
  public void shouldPreserveSelectedEnvironmentMetadata() {
    LocalDate now = LocalDate.of(2026, 9, 13);
    assertThrows(
        NullPointerException.class, () -> new CurrentEnvironmentContext(null, null, null, null));

    EnvironmentId envId = new EnvironmentId(UUID.randomUUID());
    CurrentEnvironmentContext selectedWithOsOnly =
        new CurrentEnvironmentContext(envId, DaemonOperatingSystem.MACOS, now, null);
    assertEquals(envId, selectedWithOsOnly.environmentId());
    assertEquals(DaemonOperatingSystem.MACOS, selectedWithOsOnly.operatingSystem());
    assertNull(selectedWithOsOnly.note());

    CurrentEnvironmentContext selectedWithNoteOnly =
        new CurrentEnvironmentContext(envId, null, now, "developer machine");
    assertEquals("developer machine", selectedWithNoteOnly.note());

    assertThrows(
        IllegalArgumentException.class,
        () -> new CurrentEnvironmentContext(envId, null, now, "  with whitespace  "));
  }
}
