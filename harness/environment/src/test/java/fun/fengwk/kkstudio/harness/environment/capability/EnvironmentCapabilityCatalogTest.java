package fun.fengwk.kkstudio.harness.environment.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;

import java.util.List;
import java.util.Set;

/** 固定 atomic capability ID 与 descriptor 顺序的契约测试。 */
class EnvironmentCapabilityCatalogTest {

  /** 10 个 ID 必须按 wire/执行契约固定顺序出现且全局唯一。 */
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
            EnvironmentCapabilityIds.SKILL_SYNC);

    assertEquals("2", EnvironmentCapabilityCatalog.version());
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
            "skill.sync"),
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
      assertEquals("2", descriptor.version(), descriptor.id().value());
    }
    // Schema 是参数必填性的唯一事实源；只有无目标 path 的进程执行始终需要 workdir。
    assertEquals(
        List.of(EnvironmentCapabilityIds.PROCESS_EXEC),
        EnvironmentCapabilityCatalog.descriptors().stream()
            .map(EnvironmentCapabilityDescriptor::id)
            .filter(EnvironmentCapabilityCatalog::requiresWorkdir)
            .toList());
    assertFalse(EnvironmentCapabilityCatalog.requiresWorkdir(EnvironmentCapabilityIds.FS_READ));
    assertFalse(EnvironmentCapabilityCatalog.requiresWorkdir(EnvironmentCapabilityIds.SKILL_SYNC));
    for (EnvironmentCapabilityDescriptor descriptor : EnvironmentCapabilityCatalog.descriptors()) {
      assertEquals(
          descriptor.inputSchema().required().contains("workdir"),
          EnvironmentCapabilityCatalog.requiresWorkdir(descriptor.id()));
    }
    assertEquals(
        Set.of("path"),
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_READ)
            .inputSchema()
            .required());
    assertEquals(
        Set.of("path", "content"),
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_WRITE)
            .inputSchema()
            .required());
    assertEquals(
        Set.of("path", "old_string", "new_string"),
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_EDIT)
            .inputSchema()
            .required());
    assertEquals(
        Set.of("path", "pattern"),
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_GREP)
            .inputSchema()
            .required());
    assertEquals(
        Set.of("path", "pattern"),
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_FIND)
            .inputSchema()
            .required());
    assertEquals(
        Set.of("path", "line"),
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.LSP_GOTO_DEFINITION)
            .inputSchema()
            .required());
    assertEquals(
        Set.of("path", "query"),
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.LSP_WORKSPACE_SYMBOLS)
            .inputSchema()
            .required());
    assertEquals(
        Set.of("path", "target"),
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.LSP_JAVA_DECOMPILE)
            .inputSchema()
            .required());
    assertEquals(
        Set.of("command", "workdir"),
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.PROCESS_EXEC)
            .inputSchema()
            .required());
    assertEquals(
        Set.of("packageName", "repositoryUrl", "branch", "targetCommit"),
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.SKILL_SYNC)
            .inputSchema()
            .required());

    List<EnvironmentCapabilityId> fileAndLspCapabilities =
        List.of(
            EnvironmentCapabilityIds.FS_READ,
            EnvironmentCapabilityIds.FS_WRITE,
            EnvironmentCapabilityIds.FS_EDIT,
            EnvironmentCapabilityIds.FS_GREP,
            EnvironmentCapabilityIds.FS_FIND,
            EnvironmentCapabilityIds.LSP_GOTO_DEFINITION,
            EnvironmentCapabilityIds.LSP_WORKSPACE_SYMBOLS,
            EnvironmentCapabilityIds.LSP_JAVA_DECOMPILE);
    for (EnvironmentCapabilityId id : fileAndLspCapabilities) {
      EnvironmentCapabilityDescriptor descriptor = EnvironmentCapabilityCatalog.require(id);
      assertFalse(
          descriptor.inputSchema().properties().containsKey("workdir"),
          id.value() + " must not declare workdir in properties");
      assertFalse(
          descriptor.inputSchema().required().contains("workdir"),
          id.value() + " must not declare workdir in required");
    }
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

  /** 模型可见能力保持正超时，仅内部 skill.sync 无 deadline；所有 schema 均不接受额外属性。 */
  @Test
  void onlySkillSyncHasUnboundedDeadlineWithExecutableSchema() {
    for (EnvironmentCapabilityDescriptor descriptor : EnvironmentCapabilityCatalog.descriptors()) {
      String id = descriptor.id().value();
      InputSchema schema = descriptor.inputSchema();
      assertFalse(schema.additionalProperties(), id);
      assertFalse(schema.properties().isEmpty(), id);
      assertEquals(
          descriptor.id().equals(EnvironmentCapabilityIds.SKILL_SYNC),
          descriptor.defaultTimeout().isZero(),
          id);
      assertFalse(descriptor.defaultTimeout().isNegative(), id);
    }
  }

  /** process.exec 缺少必填 workdir 时在 Capability 调用参数校验边界抛出异常。 */
  @Test
  void processExecRequiresWorkdirAtValidationBoundary() {
    EnvironmentCapabilityDescriptor processExec =
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.PROCESS_EXEC);
    EnvironmentCapabilityCall callWithoutWorkdir =
        new EnvironmentCapabilityCall("c1", "{\"command\":\"echo hi\"}");
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> callWithoutWorkdir.validateFor(processExec));
    assertTrue(error.getMessage().contains("workdir is required"), error.getMessage());
  }

  /** 文件与 LSP 能力不接受 workdir；携带 workdir 时在调用参数校验边界抛出异常。 */
  @Test
  void fileAndLspCapabilitiesRejectWorkdirAtValidationBoundary() {
    List<EnvironmentCapabilityId> fileAndLspCapabilities =
        List.of(
            EnvironmentCapabilityIds.FS_READ,
            EnvironmentCapabilityIds.FS_WRITE,
            EnvironmentCapabilityIds.FS_EDIT,
            EnvironmentCapabilityIds.FS_GREP,
            EnvironmentCapabilityIds.FS_FIND,
            EnvironmentCapabilityIds.LSP_GOTO_DEFINITION,
            EnvironmentCapabilityIds.LSP_WORKSPACE_SYMBOLS,
            EnvironmentCapabilityIds.LSP_JAVA_DECOMPILE);
    for (EnvironmentCapabilityId id : fileAndLspCapabilities) {
      EnvironmentCapabilityDescriptor descriptor = EnvironmentCapabilityCatalog.require(id);
      String validArgs =
          switch (id.value()) {
            case "fs.read" -> "{\"path\":\"/app/file.txt\"}";
            case "fs.write" -> "{\"path\":\"/app/file.txt\",\"content\":\"hello\"}";
            case "fs.edit" -> "{\"path\":\"/app/file.txt\",\"old_string\":\"a\",\"new_string\":\"b\"}";
            case "fs.grep" -> "{\"pattern\":\"foo\",\"path\":\"/app\"}";
            case "fs.find" -> "{\"pattern\":\"*.java\",\"path\":\"/app\"}";
            case "lsp.goto-definition" -> "{\"path\":\"/app/file.ts\",\"line\":1}";
            case "lsp.workspace-symbols" -> "{\"path\":\"/app/file.ts\",\"query\":\"sym\"}";
            case "lsp.java-decompile" -> "{\"path\":\"/app/A.java\",\"target\":\"cls\"}";
            default -> throw new AssertionError("unexpected id: " + id);
          };
      new EnvironmentCapabilityCall("call-ok", validArgs).validateFor(descriptor);

      String withWorkdir = validArgs.replaceFirst("\\{", "{\"workdir\":\"/tmp\",");
      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class,
              () -> new EnvironmentCapabilityCall("call-bad", withWorkdir).validateFor(descriptor),
              id.value() + " should reject workdir");
      assertTrue(error.getMessage().contains("workdir is not allowed"), error.getMessage());
    }
  }
}
