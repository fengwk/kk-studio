package fun.fengwk.kkstudio.harness.daemon.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceConfig;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceConfigCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 管理专用 Skill 来源能力的 arguments 契约：arguments 就是冻结的来源配置本身，与 catalog 声明的 schema 形状一致。
 *
 * <p>测试意图：证明三个管理能力共享同一 wire 形状、都不接受 workdir，并且 refresh 的成功结果仍是以来源快照 JSON 返回。
 */
class DaemonSkillSourceCapabilityTest {

  @TempDir Path tempDir;

  /** 三个管理能力必须共享同一份冻结来源配置 schema，且该 schema 顶层不接受任何 workdir 字段。 */
  @Test
  void managementCapabilitiesShareFrozenSourceSchemaWithoutWorkdir() {
    EnvironmentCapabilityDescriptor refresh =
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.SKILL_SOURCE_REFRESH);
    EnvironmentCapabilityDescriptor install =
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.SKILL_SOURCE_INSTALL);
    EnvironmentCapabilityDescriptor update =
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.SKILL_SOURCE_UPDATE);

    assertEquals(refresh.inputSchema(), install.inputSchema());
    assertEquals(refresh.inputSchema(), update.inputSchema());
    assertEquals(
        Set.of("sourceId", "sourceVersion", "sourceSetVersion", "type", "activeSourceIds"),
        refresh.inputSchema().required());
    assertTrue(refresh.inputSchema().properties().containsKey("scanPath"));
    assertFalse(refresh.inputSchema().properties().containsKey("workdir"));
    assertFalse(refresh.inputSchema().additionalProperties());
  }

  /** PATH 来源配置在本机存在时，refresh 的扫描结果进入冻结来源快照 JSON。 */
  @Test
  void refreshReturnsFrozenSnapshotForExistingPathSource() throws IOException {
    Path skillRoot = Files.createDirectories(tempDir.resolve("skills"));
    Path skillDirectory = Files.createDirectories(skillRoot.resolve("local"));
    Files.writeString(
        skillDirectory.resolve("SKILL.md"),
        "---\nname: local\ndescription: Local skill\n---\n# local\n");
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(tempDir.resolve("data"), tempDir);

    DaemonSkillSourceConfig config =
        DaemonSkillSourceConfig.path(
            DaemonSkillTestSupport.SOURCE_ID,
            1,
            1,
            skillRoot.toString(),
            false,
            Set.of(DaemonSkillTestSupport.SOURCE_ID));

    var snapshot = registry.refresh(config);

    assertEquals(1, snapshot.skills().size());
    assertEquals("local", snapshot.skills().get(0).name());
    assertEquals(64, snapshot.skills().get(0).contentRevision().length());
  }

  /**
   * 管理能力 arguments 就是冻结配置本身：PATH 与 GIT 的 canonical 编码都必须直接通过共享 schema 校验。
   *
   * <p>这条测试封住“编码器与 schema 各自演进”的缺口：canonical 编码省略缺席的类型专属字段后，仍必须满足 required 与 additionalProperties
   * 约束。
   */
  @Test
  void encodedSourceConfigsPassTheSharedManagementSchema() {
    EnvironmentCapabilityDescriptor descriptor =
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.SKILL_SOURCE_REFRESH);
    DaemonSkillSourceConfigCodec codec = new DaemonSkillSourceConfigCodec();

    DaemonSkillSourceConfig pathSource =
        DaemonSkillSourceConfig.path(
            DaemonSkillTestSupport.SOURCE_ID,
            3,
            5,
            "/srv/skills",
            true,
            Set.of(DaemonSkillTestSupport.SOURCE_ID));
    DaemonSkillSourceConfig gitSource =
        DaemonSkillSourceConfig.git(
            DaemonSkillTestSupport.SOURCE_ID,
            4,
            6,
            "ssh://git.example/repo.git",
            "refs/tags/v1",
            "skills",
            "b".repeat(40),
            Set.of(DaemonSkillTestSupport.SOURCE_ID));

    for (String arguments : List.of(codec.encode(pathSource), codec.encode(gitSource))) {
      EnvironmentCapabilityCall call =
          new EnvironmentCapabilityCall("call-1", arguments).validateFor(descriptor);
      assertEquals(arguments, call.argumentsJson());
      assertEquals(codec.decode(arguments), codec.decode(call.argumentsJson()));
    }
  }

  /** 三个操作对 GIT/PATH 的类型规则：install/update 只接受 GIT，refresh 接受两者。 */
  @Test
  void operationTypeRulesAreEnforcedAtTheNarrowestBoundary() {
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(tempDir.resolve("data"), tempDir);
    DaemonSkillSourceConfig pathSource =
        DaemonSkillSourceConfig.path(
            DaemonSkillTestSupport.SOURCE_ID,
            1,
            1,
            tempDir.toString(),
            false,
            Set.of(DaemonSkillTestSupport.SOURCE_ID));

    assertTrue(assertDaemonSkillFailure(() -> registry.install(pathSource)).contains("git"));
    assertTrue(assertDaemonSkillFailure(() -> registry.update(pathSource)).contains("git"));
    assertTrue(registry.snapshots().isEmpty());
  }

  private static String assertDaemonSkillFailure(Runnable action) {
    try {
      action.run();
    } catch (DaemonSkillException error) {
      return error.getMessage();
    }
    throw new AssertionError("expected DaemonSkillException");
  }
}
