package fun.fengwk.kkstudio.harness.environment.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

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

    assertEquals("1", EnvironmentCapabilityCatalog.version());
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
    assertTrue(
        EnvironmentCapabilityCatalog.descriptors().stream()
            .allMatch(
                descriptor ->
                    EnvironmentCapabilityCatalog.WORKDIR_VERSION.equals(descriptor.version())
                        || EnvironmentCapabilityCatalog.VERSION.equals(descriptor.version())));
    // workdir 语义由 capability ID 决定，不能从可能被其它能力独立使用的版本号推断。
    assertEquals(
        expected.subList(0, 9),
        EnvironmentCapabilityCatalog.descriptors().stream()
            .map(EnvironmentCapabilityDescriptor::id)
            .filter(EnvironmentCapabilityCatalog::requiresWorkdir)
            .toList());
    assertFalse(EnvironmentCapabilityCatalog.requiresWorkdir(EnvironmentCapabilityIds.SKILL_LOAD));
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
