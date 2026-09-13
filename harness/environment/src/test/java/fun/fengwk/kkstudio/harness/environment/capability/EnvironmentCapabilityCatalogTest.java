package fun.fengwk.kkstudio.harness.environment.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.schema.ObjectSchema;

import java.util.List;
import java.util.Set;

/** 固定 atomic capability ID、descriptor 顺序的契约测试。 */
class EnvironmentCapabilityCatalogTest {

  /** 11 个 ID 必须按 wire/执行契约固定顺序出现且全局唯一，并区分 workdir 版本能力。 */
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
            EnvironmentCapabilityIds.LSP_JAVA_DECOMPILE,
            EnvironmentCapabilityIds.SKILL_LOAD,
            EnvironmentCapabilityIds.MCP_LOCAL_CALL);

    assertEquals("3", EnvironmentCapabilityCatalog.version());
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
            "lsp.java-decompile",
            "skill.load",
            "mcp.local.call"),
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
              : descriptor.id().equals(EnvironmentCapabilityIds.MCP_LOCAL_CALL)
                  ? EnvironmentCapabilityCatalog.VERSION
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
    assertFalse(
        EnvironmentCapabilityCatalog.requiresWorkdir(EnvironmentCapabilityIds.MCP_LOCAL_CALL));
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
            EnvironmentCapabilityIds.SKILL_SOURCE_UPDATE,
            EnvironmentCapabilityIds.MCP_LOCAL_DISCOVER);

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
    // 前三个管理能力共享同一份冻结来源配置 schema，禁止出现第二份 wire 形状。
    assertEquals(
        1,
        EnvironmentCapabilityCatalog.managementDescriptors().stream()
            .limit(3)
            .map(EnvironmentCapabilityDescriptor::inputSchema)
            .distinct()
            .count());
    // 第四个为 local MCP 发现能力 schema。
    assertTrue(
        EnvironmentCapabilityCatalog.find(EnvironmentCapabilityIds.MCP_LOCAL_DISCOVER).isPresent());
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

  /** local MCP 调用与发现能力 schema 仅强制要求 command/cwd/environmentId/type，可选配置缺省时仍合法。 */
  @Test
  void mcpLocalCapabilitySchemasAllowOptionalConfigFields() {
    for (EnvironmentCapabilityId id :
        List.of(
            EnvironmentCapabilityIds.MCP_LOCAL_CALL, EnvironmentCapabilityIds.MCP_LOCAL_DISCOVER)) {
      ObjectSchema configSchema =
          (ObjectSchema)
              EnvironmentCapabilityCatalog.require(id).inputSchema().properties().get("config");
      assertEquals(
          Set.of("command", "cwd", "environmentId", "type"), configSchema.required(), id.value());
      assertFalse(configSchema.additionalProperties(), id.value());
      assertEquals(
          Set.of("command", "cwd", "enabled", "env", "environmentId", "timeoutMillis", "type"),
          configSchema.properties().keySet(),
          id.value());
    }

    String validJson =
        "{\"config\":{\"command\":[\"echo\"],\"cwd\":\"/opt/mcp\",\"environmentId\":\"11111111-1111-4111-8111-111111111111\",\"type\":\"local\"},"
            + "\"configVersion\":1,\"serverId\":\"11111111-1111-4111-8111-111111111111\"}";
    EnvironmentCapabilityCall call = new EnvironmentCapabilityCall("call-1", validJson);
    EnvironmentCapabilityCall validated =
        call.validateFor(
            EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.MCP_LOCAL_DISCOVER));
    assertSame(call, validated);
    assertEquals(validJson, validated.argumentsJson());

    String missingCwdJson =
        "{\"config\":{\"command\":[\"echo\"],\"environmentId\":\"11111111-1111-4111-8111-111111111111\",\"type\":\"local\"},"
            + "\"configVersion\":1,\"serverId\":\"11111111-1111-4111-8111-111111111111\"}";
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EnvironmentCapabilityCall("call-1", missingCwdJson)
                .validateFor(
                    EnvironmentCapabilityCatalog.require(
                        EnvironmentCapabilityIds.MCP_LOCAL_DISCOVER)));
  }
}
