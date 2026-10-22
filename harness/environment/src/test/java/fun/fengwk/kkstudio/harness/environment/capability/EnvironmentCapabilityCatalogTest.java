package fun.fengwk.kkstudio.harness.environment.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.schema.InputSchema;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/** 固定 atomic capability ID 与 descriptor 顺序的契约测试。 */
class EnvironmentCapabilityCatalogTest {

  /** 11 个 ID 必须按 wire/执行契约固定顺序出现且全局唯一。 */
  @Test
  void exposesStableIdsInFixedOrder() {
    List<EnvironmentCapabilityId> expected =
        List.of(
            EnvironmentCapabilityIds.FS_READ,
            EnvironmentCapabilityIds.FS_WRITE,
            EnvironmentCapabilityIds.FS_EDIT,
            EnvironmentCapabilityIds.FS_EDIT_PREVIEW,
            EnvironmentCapabilityIds.PROCESS_EXEC,
            EnvironmentCapabilityIds.FS_GREP,
            EnvironmentCapabilityIds.FS_FIND,
            EnvironmentCapabilityIds.LSP_GOTO_DEFINITION,
            EnvironmentCapabilityIds.LSP_WORKSPACE_SYMBOLS,
            EnvironmentCapabilityIds.LSP_JAVA_DECOMPILE,
            EnvironmentCapabilityIds.SKILL_SYNC);

    assertEquals("3", EnvironmentCapabilityCatalog.version());
    assertEquals(
        List.of(
            "fs.read",
            "fs.write",
            "fs.edit",
            "fs.edit.preview",
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
      assertEquals("3", descriptor.version(), descriptor.id().value());
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
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_EDIT).inputSchema(),
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_EDIT_PREVIEW)
            .inputSchema());
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

  /** skill.sync 的 targetCommit 文案必须如实声明后端只接受 40 位小写 SHA-1，避免 64 位 SHA-256 的误导。 */
  @Test
  void skillSyncSchemaDocumentsFortyCharacterSha1TargetCommit() {
    String description =
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.SKILL_SYNC)
            .inputSchema()
            .properties()
            .get("targetCommit")
            .description();

    assertTrue(description.contains("40"), description);
    assertTrue(description.contains("SHA-1"), description);
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
            EnvironmentCapabilityIds.FS_EDIT_PREVIEW,
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
            case "fs.edit",
                "fs.edit.preview" -> "{\"path\":\"/app/file.txt\",\"old_string\":\"a\",\"new_string\":\"b\"}";
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

  /** 测试意图：验证全量 capability 的默认超时契约。 */
  @Test
  void enforcesStandardDefaultTimeouts() {
    assertEquals(
        Duration.ofSeconds(60),
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_READ).defaultTimeout());
    assertEquals(
        Duration.ofSeconds(60),
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_WRITE).defaultTimeout());
    assertEquals(
        Duration.ofSeconds(60),
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_EDIT).defaultTimeout());
    assertEquals(
        Duration.ofSeconds(60),
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_EDIT_PREVIEW)
            .defaultTimeout());
    assertEquals(
        Duration.ofSeconds(300),
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.PROCESS_EXEC)
            .defaultTimeout());
    assertEquals(
        Duration.ofSeconds(15),
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_GREP).defaultTimeout());
    assertEquals(
        Duration.ofSeconds(15),
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_FIND).defaultTimeout());
    assertEquals(
        Duration.ofSeconds(180),
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.LSP_GOTO_DEFINITION)
            .defaultTimeout());
    assertEquals(
        Duration.ofSeconds(180),
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.LSP_WORKSPACE_SYMBOLS)
            .defaultTimeout());
    assertEquals(
        Duration.ofSeconds(180),
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.LSP_JAVA_DECOMPILE)
            .defaultTimeout());
    assertEquals(
        Duration.ZERO,
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.SKILL_SYNC).defaultTimeout());
  }

  /** 测试意图：验证 fs.grep 与 fs.find 的 schema 文案如实说明默认超时为 15 秒。 */
  @Test
  void grepAndFindSchemasDocumentFifteenSecondDefaultTimeout() {
    String grepDescription =
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_GREP)
            .inputSchema()
            .properties()
            .get("timeout_seconds")
            .description();
    assertTrue(grepDescription.contains("Defaults to 15."), grepDescription);

    String findDescription =
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_FIND)
            .inputSchema()
            .properties()
            .get("timeout_seconds")
            .description();
    assertTrue(findDescription.contains("Defaults to 15."), findDescription);
  }

  /** 测试意图：验证 Java classpath 加载的 schema 与 Daemon capabilities 镜像文件完全一致。 */
  @Test
  void javaSchemasMirrorDaemonCapabilitySchemas() throws IOException {
    Path projectRoot = findProjectRootDir();
    Path daemonCapabilitiesDir = projectRoot.resolve("daemon/resources/capabilities");
    for (EnvironmentCapabilityDescriptor descriptor : EnvironmentCapabilityCatalog.descriptors()) {
      String fileName = descriptor.id().value() + ".schema.json";
      Path daemonSchemaPath = daemonCapabilitiesDir.resolve(fileName);
      assertTrue(Files.exists(daemonSchemaPath), "daemon schema must exist: " + daemonSchemaPath);
      String daemonSchema =
          Files.readString(daemonSchemaPath, StandardCharsets.UTF_8).stripTrailing();
      String javaSchemaResource =
          "/fun/fengwk/kkstudio/harness/environment/capability/schemas/" + fileName;
      try (InputStream input =
          EnvironmentCapabilityCatalog.class.getResourceAsStream(javaSchemaResource)) {
        assertNotNull(input, "missing java schema resource: " + javaSchemaResource);
        String javaSchema =
            new String(input.readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
        assertEquals(
            daemonSchema, javaSchema, "Java and daemon schemas must match for " + fileName);
      }
    }
  }

  private static Path findProjectRootDir() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      if (Files.isDirectory(current.resolve("daemon/resources/capabilities"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException(
        "cannot find project root containing daemon/resources/capabilities");
  }
}
