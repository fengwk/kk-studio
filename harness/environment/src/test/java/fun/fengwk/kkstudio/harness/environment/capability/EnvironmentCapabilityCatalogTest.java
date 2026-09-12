package fun.fengwk.kkstudio.harness.environment.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** 固定 atomic capability ID、descriptor 顺序的契约测试。 */
class EnvironmentCapabilityCatalogTest {

  /** 10 个 ID 必须按 wire/执行契约固定顺序出现且全局唯一，并区分 workdir 版本能力。 */
  @Test
  void exposesStableIdsInFixedOrder() {
    List<EnvironmentCapabilityId> expected =
        List.of(
            EnvironmentCapabilityIds.FS_READ,
            EnvironmentCapabilityIds.FS_WRITE,
            EnvironmentCapabilityIds.FS_APPLY_EDIT,
            EnvironmentCapabilityIds.PROCESS_EXEC,
            EnvironmentCapabilityIds.FS_SEARCH,
            EnvironmentCapabilityIds.FS_FIND,
            EnvironmentCapabilityIds.LSP_GOTO_DEFINITION,
            EnvironmentCapabilityIds.LSP_WORKSPACE_SYMBOLS,
            EnvironmentCapabilityIds.LSP_JAVA_DECOMPILE,
            EnvironmentCapabilityIds.SKILL_LOAD);

    assertEquals("2", EnvironmentCapabilityCatalog.version());
    assertEquals(
        List.of(
            "fs.read",
            "fs.write",
            "fs.apply-edit",
            "process.exec",
            "fs.search",
            "fs.find",
            "lsp.goto-definition",
            "lsp.workspace-symbols",
            "lsp.java-decompile",
            "skill.load"),
        expected.stream().map(EnvironmentCapabilityId::value).toList());
    assertEquals(
        expected,
        EnvironmentCapabilityCatalog.descriptors().stream()
            .map(EnvironmentCapabilityDescriptor::id)
            .toList());
    assertEquals(
        expected.size(),
        EnvironmentCapabilityCatalog.descriptors().stream()
            .map(EnvironmentCapabilityDescriptor::id)
            .distinct()
            .count());
    assertEquals(
        expected.size(), EnvironmentCapabilityCatalog.descriptors().stream().distinct().count());
    for (EnvironmentCapabilityDescriptor descriptor : EnvironmentCapabilityCatalog.descriptors()) {
      String expectedVersion =
          descriptor.id().equals(EnvironmentCapabilityIds.SKILL_LOAD)
              ? EnvironmentCapabilityCatalog.SKILL_LOAD_VERSION
              : EnvironmentCapabilityCatalog.WORKDIR_VERSION;
      assertEquals(expectedVersion, descriptor.version(), descriptor.id().value());
    }
    // workdir 语义由 capability ID 决定，不能从可能被其它能力独立使用的版本号推断。
    assertEquals(
        expected.subList(0, 9),
        EnvironmentCapabilityCatalog.descriptors().stream()
            .map(EnvironmentCapabilityDescriptor::id)
            .filter(EnvironmentCapabilityCatalog::requiresWorkdir)
            .toList());
    assertFalse(EnvironmentCapabilityCatalog.requiresWorkdir(EnvironmentCapabilityIds.SKILL_LOAD));
  }

  /**
   * 管理专用能力必须在固定顺序中注册、与模型可见能力分离、无需 workdir，且仍能被 find 解析。
   *
   * <p>它们复用 INVOKE/CANCEL/结果通道，但绝不进入模型工具目录，因此 descriptor 列表与 management 列表必须严格区分。
   */
  @Test
  void exposesManagementOnlySkillSourceCapabilities() {
    List<EnvironmentCapabilityId> expected =
        List.of(
            EnvironmentCapabilityIds.SKILL_SOURCE_REFRESH,
            EnvironmentCapabilityIds.SKILL_SOURCE_INSTALL,
            EnvironmentCapabilityIds.SKILL_SOURCE_UPDATE);

    assertEquals(
        expected,
        EnvironmentCapabilityCatalog.managementDescriptors().stream()
            .map(EnvironmentCapabilityDescriptor::id)
            .toList());
    assertEquals(EnvironmentCapabilityIds.MANAGEMENT_ONLY, Set.copyOf(expected));
    for (EnvironmentCapabilityId id : expected) {
      assertTrue(EnvironmentCapabilityCatalog.find(id).isPresent(), id.value());
      assertFalse(EnvironmentCapabilityCatalog.requiresWorkdir(id), id.value());
      assertTrue(
          EnvironmentCapabilityCatalog.descriptors().stream()
              .noneMatch(descriptor -> descriptor.id().equals(id)),
          id.value());
    }
    // 管理能力与模型可见能力共享同一份冻结来源配置 schema，禁止出现第二份 wire 形状。
    assertEquals(
        1,
        Stream.concat(
                EnvironmentCapabilityCatalog.managementDescriptors().stream(),
                EnvironmentCapabilityCatalog.managementDescriptors().stream())
            .map(EnvironmentCapabilityDescriptor::inputSchema)
            .distinct()
            .count());
  }

  /** descriptor 是 execution 的唯一事实源；可通过 find 与 require 查询。 */
  @Test
  void resolvesDescriptors() {
    assertSame(
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_READ),
        EnvironmentCapabilityCatalog.find(EnvironmentCapabilityIds.FS_READ).orElseThrow());
    assertTrue(
        EnvironmentCapabilityCatalog.find(new EnvironmentCapabilityId("unknown.capability"))
            .isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            EnvironmentCapabilityCatalog.require(
                new EnvironmentCapabilityId("unknown.capability")));
  }
}
