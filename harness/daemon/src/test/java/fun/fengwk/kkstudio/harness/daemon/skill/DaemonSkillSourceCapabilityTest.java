package fun.fengwk.kkstudio.harness.daemon.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceConfig;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceConfigCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceSnapshot;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceSnapshotCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 管理专用 Skill 来源能力的 arguments 契约：arguments 就是冻结的来源配置本身，与 catalog 声明的 schema 形状一致。
 *
 * <p>测试意图：证明三个管理能力共享同一 wire 形状、都不接受 workdir，并且 refresh 的成功结果仍是以来源快照 JSON 返回； 同时测试
 * REFRESH/INSTALL/UPDATE 执行分派、取消控制以及异常不透明收敛。
 */
class DaemonSkillSourceCapabilityTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

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

  /** 测试意图：请求描述符不匹配能力自身描述符时，execute 必须抛出 IllegalArgumentException。 */
  @Test
  void rejectsRequestWithMismatchedDescriptor() {
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(tempDir.resolve("data"), tempDir);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      DaemonSkillSourceCapability capability =
          new DaemonSkillSourceCapability(
              DaemonSkillSourceCapability.Operation.REFRESH, registry, executor);
      EnvironmentCapabilityDescriptor wrongDescriptor =
          EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.SKILL_LOAD);
      String loadArgs =
          """
          {"sourceId": "%s", "name": "alpha", "revision": "%s"}
          """
              .formatted(DaemonSkillTestSupport.SOURCE_ID, "a".repeat(64));
      EnvironmentCapabilityExecutionRequest request =
          new EnvironmentCapabilityExecutionRequest(
              wrongDescriptor,
              new EnvironmentCapabilityCall("call-1", loadArgs),
              Duration.ofSeconds(5));

      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class,
              () -> capability.execute(request, new RecordingListener()));
      assertTrue(error.getMessage().contains("descriptor"));
    } finally {
      executor.shutdownNow();
    }
  }

  /** 测试意图：REFRESH/INSTALL/UPDATE 三种操作能通过 capability.execute 正确分派并以快照 JSON 返回。 */
  @Test
  void executesOperationsAndReturnsSnapshotJson() throws Exception {
    Path skillRoot = Files.createDirectories(tempDir.resolve("skills-exec"));
    Path skillDirectory = Files.createDirectories(skillRoot.resolve("demo"));
    Files.writeString(
        skillDirectory.resolve("SKILL.md"),
        "---\nname: demo\ndescription: Demo skill\n---\n# Demo\n");
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(tempDir.resolve("data"), tempDir);
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    try {
      DaemonSkillSourceConfig config =
          DaemonSkillSourceConfig.path(
              DaemonSkillTestSupport.SOURCE_ID,
              1,
              1,
              skillRoot.toString(),
              false,
              Set.of(DaemonSkillTestSupport.SOURCE_ID));
      String arguments = new DaemonSkillSourceConfigCodec().encode(config);

      // 1. REFRESH 成功
      DaemonSkillSourceCapability refreshCap =
          new DaemonSkillSourceCapability(
              DaemonSkillSourceCapability.Operation.REFRESH, registry, executor);
      EnvironmentCapabilityResult refreshResult = execute(refreshCap, arguments);
      assertFalse(refreshResult.error());
      DaemonSkillSourceSnapshot snapshot =
          new DaemonSkillSourceSnapshotCodec()
              .decodeNode(MAPPER.readTree(json(refreshResult)), "snapshot");
      assertEquals(1, snapshot.skills().size());
      assertEquals("demo", snapshot.skills().get(0).name());

      // 2. INSTALL 针对 PATH 来源失败（类型规则约束），返回固定的 OPERATION_FAILED_MESSAGE
      DaemonSkillSourceCapability installCap =
          new DaemonSkillSourceCapability(
              DaemonSkillSourceCapability.Operation.INSTALL, registry, executor);
      EnvironmentCapabilityResult installResult = execute(installCap, arguments);
      assertTrue(installResult.error());
      assertEquals(
          "Error: " + DaemonSkillSourceCapability.OPERATION_FAILED_MESSAGE, text(installResult));

      // 3. UPDATE 针对 PATH 来源失败，同样返回固定的 OPERATION_FAILED_MESSAGE
      DaemonSkillSourceCapability updateCap =
          new DaemonSkillSourceCapability(
              DaemonSkillSourceCapability.Operation.UPDATE, registry, executor);
      EnvironmentCapabilityResult updateResult = execute(updateCap, arguments);
      assertTrue(updateResult.error());
      assertEquals(
          "Error: " + DaemonSkillSourceCapability.OPERATION_FAILED_MESSAGE, text(updateResult));

      // 4. INSTALL 针对 GIT 来源成功执行并返回快照
      Path control = Files.createDirectories(tempDir.resolve("fake-git-control"));
      DaemonSkillRegistry gitRegistry = fakeGitRegistry(control);
      DaemonSkillSourceConfig gitConfig =
          DaemonSkillSourceConfig.git(
              DaemonSkillTestSupport.SOURCE_ID,
              1,
              1,
              "fake-git://instant/my-skill",
              null,
              null,
              null,
              Set.of(DaemonSkillTestSupport.SOURCE_ID));
      String gitArguments = new DaemonSkillSourceConfigCodec().encode(gitConfig);

      DaemonSkillSourceCapability gitInstallCap =
          new DaemonSkillSourceCapability(
              DaemonSkillSourceCapability.Operation.INSTALL, gitRegistry, executor);
      EnvironmentCapabilityResult gitInstallResult = execute(gitInstallCap, gitArguments);
      assertFalse(gitInstallResult.error());
      DaemonSkillSourceSnapshot gitSnapshot =
          new DaemonSkillSourceSnapshotCodec()
              .decodeNode(MAPPER.readTree(json(gitInstallResult)), "git-snapshot");
      assertEquals(1, gitSnapshot.skills().size());
      assertEquals("my-skill", gitSnapshot.skills().get(0).name());

      // 5. UPDATE 针对 GIT 来源成功执行并返回快照
      DaemonSkillSourceConfig updateConfig =
          DaemonSkillSourceConfig.git(
              DaemonSkillTestSupport.SOURCE_ID,
              2,
              1,
              "fake-git://instant/my-skill",
              null,
              null,
              gitSnapshot.sourceRevision(),
              Set.of(DaemonSkillTestSupport.SOURCE_ID));
      String updateArguments = new DaemonSkillSourceConfigCodec().encode(updateConfig);

      DaemonSkillSourceCapability gitUpdateCap =
          new DaemonSkillSourceCapability(
              DaemonSkillSourceCapability.Operation.UPDATE, gitRegistry, executor);
      EnvironmentCapabilityResult gitUpdateResult = execute(gitUpdateCap, updateArguments);
      assertFalse(gitUpdateResult.error());
      DaemonSkillSourceSnapshot updateSnapshot =
          new DaemonSkillSourceSnapshotCodec()
              .decodeNode(MAPPER.readTree(json(gitUpdateResult)), "update-snapshot");
      assertEquals(1, updateSnapshot.skills().size());
      assertEquals("my-skill", updateSnapshot.skills().get(0).name());
      assertEquals(2L, updateSnapshot.sourceVersion());
    } finally {
      executor.shutdownNow();
    }
  }

  /** 测试意图：执行 handle 的 cancel 应当中断 worker 线程并以 Operation cancelled 终结，且 cancel 幂等。 */
  @Test
  void supportsCancellationOnSourceExecutionHandle() throws Exception {
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(tempDir.resolve("data"), tempDir);
    CountDownLatch startedLatch = new CountDownLatch(1);
    CountDownLatch releaseLatch = new CountDownLatch(1);

    ExecutorService blockedExecutor = Executors.newSingleThreadExecutor();
    try {
      DaemonSkillSourceCapability capability =
          new DaemonSkillSourceCapability(
              DaemonSkillSourceCapability.Operation.REFRESH, registry, blockedExecutor);
      EnvironmentCapabilityDescriptor descriptor = capability.descriptor();

      DaemonSkillSourceConfig config =
          DaemonSkillSourceConfig.path(
              DaemonSkillTestSupport.SOURCE_ID,
              1,
              1,
              tempDir.toString(),
              false,
              Set.of(DaemonSkillTestSupport.SOURCE_ID));
      String arguments = new DaemonSkillSourceConfigCodec().encode(config);

      // 先占满线程
      blockedExecutor.submit(
          () -> {
            startedLatch.countDown();
            try {
              releaseLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
          });
      assertTrue(startedLatch.await(5, TimeUnit.SECONDS));

      EnvironmentCapabilityExecutionRequest request =
          new EnvironmentCapabilityExecutionRequest(
              descriptor,
              new EnvironmentCapabilityCall("call-cancel", arguments),
              Duration.ofSeconds(10));

      RecordingListener listener = new RecordingListener();
      EnvironmentCapabilityExecutionHandle handle = capability.execute(request, listener);

      assertFalse(handle.isCancelled());
      handle.cancel();
      assertTrue(handle.isCancelled());

      // 重复 cancel 幂等
      handle.cancel();
      assertTrue(handle.isCancelled());

      assertTrue(listener.await());
      assertNotNull(listener.result);
      assertTrue(listener.result.error());
      assertTrue(text(listener.result).contains("cancelled"));
    } finally {
      releaseLatch.countDown();
      blockedExecutor.shutdownNow();
    }
  }

  private EnvironmentCapabilityResult execute(
      DaemonSkillSourceCapability capability, String argumentsJson) throws Exception {
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionRequest request =
        new EnvironmentCapabilityExecutionRequest(
            capability.descriptor(),
            new EnvironmentCapabilityCall("call-1", argumentsJson),
            Duration.ofSeconds(10));
    capability.execute(request, listener);
    assertTrue(listener.await(), "execution timed out");
    return listener.result;
  }

  private static String text(EnvironmentCapabilityResult result) {
    return result.contents().stream()
        .filter(TextResultContent.class::isInstance)
        .map(TextResultContent.class::cast)
        .map(TextResultContent::text)
        .findFirst()
        .orElse("");
  }

  private static String json(EnvironmentCapabilityResult result) {
    return result.contents().stream()
        .filter(JsonResultContent.class::isInstance)
        .map(JsonResultContent.class::cast)
        .map(JsonResultContent::json)
        .findFirst()
        .orElse("");
  }

  private static final class RecordingListener implements EnvironmentCapabilityExecutionListener {
    private final CountDownLatch completed = new CountDownLatch(1);
    private volatile EnvironmentCapabilityResult result;
    private volatile Throwable error;

    @Override
    public void onComplete(EnvironmentCapabilityResult result) {
      this.result = result;
      completed.countDown();
    }

    @Override
    public void onError(Throwable error) {
      this.error = error;
      completed.countDown();
    }

    boolean await() throws InterruptedException {
      return completed.await(10, TimeUnit.SECONDS);
    }
  }

  private DaemonSkillRegistry fakeGitRegistry(Path control) {
    Path gitDataDir = control.resolve("data");
    List<String> command =
        List.of(
            System.getProperty("java.home") + "/bin/java",
            "-D" + FakeGitCommand.CONTROL_DIRECTORY_PROPERTY + "=" + control,
            "-cp",
            System.getProperty("java.class.path"),
            FakeGitCommand.class.getName());
    return DaemonSkillRegistry.open(gitDataDir, tempDir, command);
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
