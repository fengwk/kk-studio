package fun.fengwk.kkstudio.harness.environment.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;

import java.util.List;

/** 固定 atomic capability ID 与 descriptor 顺序的契约测试。 */
class EnvironmentCapabilityCatalogTest {

  /** 9 个 ID 必须按 wire/执行契约固定顺序出现且全局唯一。 */
  @Test
  void exposesStableIdsInFixedOrder() {
    List<EnvironmentCapabilityId> expected =
        List.of(
            EnvironmentCapabilityIds.FS_READ,
            EnvironmentCapabilityIds.FS_WRITE,
            EnvironmentCapabilityIds.FS_EDIT,
            EnvironmentCapabilityIds.PROCESS_EXEC,
            EnvironmentCapabilityIds.FS_GREP,
            EnvironmentCapabilityIds.FS_FIND,
            EnvironmentCapabilityIds.LSP_GOTO_DEFINITION,
            EnvironmentCapabilityIds.LSP_WORKSPACE_SYMBOLS,
            EnvironmentCapabilityIds.LSP_JAVA_DECOMPILE);

    assertEquals("1", EnvironmentCapabilityCatalog.version());
    assertEquals(
        List.of(
            "fs.read",
            "fs.write",
            "fs.edit",
            "process.exec",
            "fs.grep",
            "fs.find",
            "lsp.goto-definition",
            "lsp.workspace-symbols",
            "lsp.java-decompile"),
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
      assertEquals("1", descriptor.version(), descriptor.id().value());
    }
    // workdir 语义由 capability ID 决定，不能从可能被其它能力独立使用的版本号推断。
    assertEquals(
        expected,
        EnvironmentCapabilityCatalog.descriptors().stream()
            .map(EnvironmentCapabilityDescriptor::id)
            .filter(EnvironmentCapabilityCatalog::requiresWorkdir)
            .toList());
    assertTrue(EnvironmentCapabilityCatalog.requiresWorkdir(EnvironmentCapabilityIds.FS_READ));
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

  /** 每个模型可见能力都必须携带非空 object input schema 与正超时，且不接受额外属性。 */
  @Test
  void everyDescriptorCarriesExecutableSchemaAndPositiveTimeout() {
    for (EnvironmentCapabilityDescriptor descriptor : EnvironmentCapabilityCatalog.descriptors()) {
      String id = descriptor.id().value();
      InputSchema schema = descriptor.inputSchema();
      assertFalse(schema.additionalProperties(), id);
      assertFalse(schema.properties().isEmpty(), id);
      assertFalse(descriptor.timeout().isZero() || descriptor.timeout().isNegative(), id);
    }
  }
}
