package fun.fengwk.kkstudio.platform.harness.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.DaemonHostInfo;
import fun.fengwk.kkstudio.platform.daemon.DaemonOperatingSystem;

import java.util.UUID;

/** CurrentEnvironmentContext 的不可变契约与宿主事实校验测试。 */
public class CurrentEnvironmentContextTest {

  // 测试意图: 未选择 Environment 的上下文不允许携带任何宿主事实。
  @Test
  public void shouldRejectHostFactsWithoutSelectedEnvironment() {
    CurrentEnvironmentContext unselected =
        new CurrentEnvironmentContext(null, null, null, null, null, null, null);
    assertNull(unselected.environmentId());
    assertNull(unselected.environmentName());
    assertNull(unselected.operatingSystem());
    assertNull(unselected.userName());
    assertNull(unselected.homeDirectory());
    assertNull(unselected.tempDirectory());
    assertNull(unselected.note());

    assertThrows(
        IllegalArgumentException.class,
        () -> new CurrentEnvironmentContext(null, "env", null, null, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CurrentEnvironmentContext(
                null, null, DaemonOperatingSystem.LINUX, null, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CurrentEnvironmentContext(null, null, null, "dev", null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CurrentEnvironmentContext(null, null, null, null, "/home/dev", null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CurrentEnvironmentContext(null, null, null, null, null, "/tmp/kk-studio", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CurrentEnvironmentContext(null, null, null, null, null, null, "some-note"));
  }

  // 测试意图: 已选环境必须携带 name，且 userName/homeDirectory/tempDirectory/note 复用宿主事实的结构校验。
  @Test
  public void shouldValidateSelectedEnvironmentFacts() {
    EnvironmentId envId = new EnvironmentId(UUID.randomUUID());
    assertThrows(
        IllegalArgumentException.class,
        () -> new CurrentEnvironmentContext(envId, null, null, null, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CurrentEnvironmentContext(envId, "   ", null, null, null, null, null));

    CurrentEnvironmentContext selected =
        new CurrentEnvironmentContext(
            envId,
            "nas-dev",
            DaemonOperatingSystem.MACOS,
            "dev",
            "/Users/dev",
            "/tmp/kk-studio",
            "developer machine");
    assertEquals(envId, selected.environmentId());
    assertEquals("nas-dev", selected.environmentName());
    assertEquals(DaemonOperatingSystem.MACOS, selected.operatingSystem());
    assertEquals("dev", selected.userName());
    assertEquals("/Users/dev", selected.homeDirectory());
    assertEquals("/tmp/kk-studio", selected.tempDirectory());
    assertEquals("developer machine", selected.note());

    // 宿主事实是可选项，缺失时保持 null 而不是伪造默认值。
    CurrentEnvironmentContext nameOnly =
        new CurrentEnvironmentContext(envId, "nas-dev", null, null, null, null, null);
    assertNull(nameOnly.operatingSystem());
    assertNull(nameOnly.userName());
    assertNull(nameOnly.homeDirectory());
    assertNull(nameOnly.tempDirectory());

    assertThrows(
        IllegalArgumentException.class,
        () -> new CurrentEnvironmentContext(envId, "nas-dev", null, " dev ", null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CurrentEnvironmentContext(
                envId, "nas-dev", null, null, "relative/home", null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CurrentEnvironmentContext(
                envId, "nas-dev", null, null, null, "relative/tmp", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CurrentEnvironmentContext(envId, "nas-dev", null, null, null, null, " "));
  }

  // 测试意图: 未选择 Environment 的工厂不携带任何宿主事实。
  @Test
  public void shouldBuildEmptyContextWhenNoEnvironmentSelected() {
    CurrentEnvironmentContext none = CurrentEnvironmentContext.none();

    assertNull(none.environmentId());
    assertNull(none.environmentName());
    assertNull(none.operatingSystem());
    assertNull(none.userName());
    assertNull(none.homeDirectory());
    assertNull(none.tempDirectory());
    assertNull(none.note());
  }

  // 测试意图: 已选环境的宿主事实只来自 READY payload，宿主从未 READY 时所有可选事实保持 null（绝不伪造）。
  @Test
  public void shouldMapHostFactsAndOmitMissingFacts() {
    EnvironmentId envId = new EnvironmentId(UUID.randomUUID());
    CurrentEnvironmentContext unreported =
        CurrentEnvironmentContext.selected(envId, "nas-dev", null, null);
    assertEquals("nas-dev", unreported.environmentName());
    assertNull(unreported.operatingSystem());
    assertNull(unreported.userName());
    assertNull(unreported.homeDirectory());
    assertNull(unreported.tempDirectory());
    assertNull(unreported.note());

    DaemonHostInfo hostFacts =
        new DaemonHostInfo(
            DaemonOperatingSystem.LINUX,
            "America/Los_Angeles",
            "dev",
            "/home/dev",
            "/tmp/kk-studio",
            "amd64");
    CurrentEnvironmentContext reported =
        CurrentEnvironmentContext.selected(envId, "nas-dev", "Linux environment.", hostFacts);
    assertEquals(DaemonOperatingSystem.LINUX, reported.operatingSystem());
    assertEquals("dev", reported.userName());
    assertEquals("/home/dev", reported.homeDirectory());
    assertEquals("/tmp/kk-studio", reported.tempDirectory());
    assertEquals("Linux environment.", reported.note());
  }
}
