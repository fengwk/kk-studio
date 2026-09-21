package fun.fengwk.kkstudio.platform.environment.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentEvent;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentSkillState;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SkillPromptPathResolver 的精确安装判定测试。
 *
 * <p>本地稳定路径是可重建的缓存事实，只有「投影状态为 installed 且已安装 commit 与 package currentCommit 完全一致」时才允许
 * 暴露给模型；其余一切情况都必须回退 platform URI。
 */
class SkillPromptPathResolverTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
  private static final String OTHER_COMMIT = "fedcba9876543210fedcba9876543210fedcba98";
  private static final Instant NOW = Instant.parse("2026-09-21T06:00:00Z");
  private static final DaemonCapabilities CAPABILITIES =
      new DaemonCapabilities(
          DaemonCapabilities.VERSION,
          new DaemonEnvironmentInfo(
              DaemonOperatingSystem.LINUX, "UTC", "dev", "/home/dev", "Linux environment."));

  private EnvironmentRegistry registry;
  private SkillPromptPathResolver resolver;

  @BeforeEach
  void setUp() {
    registry = mock(EnvironmentRegistry.class);
    resolver = new SkillPromptPathResolver(registry);
  }

  /** 测试意图：投影精确记录 currentCommit 已安装时，模型可见路径使用 Daemon 上的本地稳定路径。 */
  @Test
  void exactInstalledCommitYieldsLocalPath() {
    bind(List.of(EnvironmentSkillState.installed("pkg", COMMIT, "/home/dev/.kkstudio/skills/pkg")));

    assertEquals(
        "/home/dev/.kkstudio/skills/pkg/dev/SKILL.md", resolver.resolve(ENV, "pkg", "dev", COMMIT));
  }

  /** 测试意图：未选环境、无连接行、从未同步、同步失败、提交不一致与本地路径缺失全部回退 platform URI。 */
  @Test
  void everyNonExactCaseFallsBackToPlatformUri() {
    String expected = "kkstudio:/skills/pkg/dev/SKILL.md";

    // 未选择 Environment 时不查询 registry。
    assertEquals(expected, resolver.resolve(null, "pkg", "dev", COMMIT));
    assertEquals(expected, resolver.resolve(ENV, "pkg", "dev", null));

    // 无连接行。
    when(registry.find(any())).thenReturn(Optional.empty());
    assertEquals(expected, resolver.resolve(ENV, "pkg", "dev", COMMIT));

    // 从未同步过。
    bind(List.of());
    assertEquals(expected, resolver.resolve(ENV, "pkg", "dev", COMMIT));

    // 同步失败：即使保留了上一次成功安装的事实，只要 currentCommit 不一致就回退。
    bind(
        List.of(
            EnvironmentSkillState.failed(
                "pkg", EnvironmentSkillState.installed("pkg", COMMIT, "/home/dev/old"), "boom")));
    assertEquals(expected, resolver.resolve(ENV, "pkg", "dev", COMMIT));

    // 已安装 commit 与 package currentCommit 不一致。
    bind(List.of(EnvironmentSkillState.installed("pkg", COMMIT, "/home/dev/.kkstudio/skills/pkg")));
    assertEquals(expected, resolver.resolve(ENV, "pkg", "dev", OTHER_COMMIT));

    // 其它 Package 的安装事实不参与该 Package 的判定。
    bind(
        List.of(
            EnvironmentSkillState.installed("other", COMMIT, "/home/dev/.kkstudio/skills/other")));
    assertEquals(expected, resolver.resolve(ENV, "pkg", "dev", COMMIT));
  }

  /** 测试意图：platform URI 是稳定的托管路径，与 registry 状态无关。 */
  @Test
  void platformPathIsStable() {
    assertEquals(
        "kkstudio:/skills/pkg/dev/SKILL.md", SkillPromptPathResolver.platformPath("pkg", "dev"));
  }

  /** 测试意图：package 与 skill 名是必填事实，缺失时立即失败而不是产生含糊路径。 */
  @Test
  void missingNamesAreRejected() {
    assertThrows(NullPointerException.class, () -> resolver.resolve(ENV, null, "dev", COMMIT));
    assertThrows(NullPointerException.class, () -> resolver.resolve(ENV, "pkg", null, COMMIT));
  }

  private void bind(List<EnvironmentSkillState> skillState) {
    when(registry.find(any()))
        .thenReturn(
            Optional.of(
                new EnvironmentConnection(
                    ENV,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    LiveEnvironmentStatus.READY,
                    CAPABILITIES,
                    skillState,
                    List.of(
                        new EnvironmentEvent(
                            NOW,
                            EnvironmentEvent.LEVEL_INFO,
                            EnvironmentEvent.TYPE_READY,
                            "environment ready")),
                    NOW,
                    NOW.plusSeconds(60))));
  }
}
