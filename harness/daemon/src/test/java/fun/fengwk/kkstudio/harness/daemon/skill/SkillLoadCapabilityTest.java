package fun.fengwk.kkstudio.harness.daemon.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResultCodes;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceConfig;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceConfigCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** {@link SkillLoadCapability} 行为测试： 验证参数校验、描述符校验、正常加载、不可用版本 coded error、取消路径与异常收敛。 */
class SkillLoadCapabilityTest {

  private static final UUID SOURCE_ID = DaemonSkillTestSupport.SOURCE_ID;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path tempDir;

  private ExecutorService executor;
  private DaemonSkillRegistry registry;
  private SkillLoadCapability capability;
  private EnvironmentCapabilityDescriptor descriptor;

  @BeforeEach
  void setUp() {
    executor = Executors.newVirtualThreadPerTaskExecutor();
    registry = DaemonSkillRegistry.open(tempDir.resolve("data"), tempDir);
    capability = new SkillLoadCapability(registry, executor);
    descriptor = capability.descriptor();
  }

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
  }

  /** 测试意图：调用时请求描述符与能力描述符不一致时必须立即抛出 IllegalArgumentException。 */
  @Test
  void rejectsRequestWithMismatchedDescriptor() {
    EnvironmentCapabilityDescriptor otherDescriptor =
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.SKILL_SOURCE_REFRESH);
    String validRefreshArgs =
        new DaemonSkillSourceConfigCodec()
            .encode(
                DaemonSkillSourceConfig.path(SOURCE_ID, 1, 1, "/skills", false, Set.of(SOURCE_ID)));
    EnvironmentCapabilityExecutionRequest request =
        new EnvironmentCapabilityExecutionRequest(
            otherDescriptor,
            new EnvironmentCapabilityCall("call-1", validRefreshArgs),
            Duration.ofSeconds(5));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> capability.execute(request, new RecordingListener()));
    assertTrue(error.getMessage().contains("descriptor"));
  }

  /** 测试意图：sourceId 非 UUID 或非小写标准格式、name 为空白或含控制字符、revision 长度非 64 或非十六进制时，均返回固定的非法请求错误。 */
  @Test
  void rejectsArgumentsWithInvalidTypesOrFormats() throws Exception {
    String validRevision = "a".repeat(64);
    String[] invalidCases =
        new String[] {
          // sourceId 为空白字符串
          """
          {"sourceId": "   ", "name": "valid-name", "revision": "%s"}
          """
              .formatted(validRevision),
          // sourceId 不是 UUID 形状
          """
          {"sourceId": "not-a-uuid", "name": "valid-name", "revision": "%s"}
          """
              .formatted(validRevision),
          // 大写 UUID（非 canonical）
          """
          {"sourceId": "%s", "name": "valid-name", "revision": "%s"}
          """
              .formatted("ABCDEF01-2345-4789-ABCD-EF0123456789", validRevision),
          // name 为空白
          """
          {"sourceId": "%s", "name": "   ", "revision": "%s"}
          """
              .formatted(SOURCE_ID, validRevision),
          // name 包含换行控制字符
          """
          {"sourceId": "%s", "name": "invalid\\nname", "revision": "%s"}
          """
              .formatted(SOURCE_ID, validRevision),
          // name 带有首尾空白
          """
          {"sourceId": "%s", "name": "  spaced-name  ", "revision": "%s"}
          """
              .formatted(SOURCE_ID, validRevision),
          // revision 为空白
          """
          {"sourceId": "%s", "name": "valid-name", "revision": "   "}
          """
              .formatted(SOURCE_ID),
          // revision 长度不足 64
          """
          {"sourceId": "%s", "name": "valid-name", "revision": "1234"}
          """
              .formatted(SOURCE_ID),
          // revision 含有非十六进制字符
          """
          {"sourceId": "%s", "name": "valid-name", "revision": "%s"}
          """
              .formatted(SOURCE_ID, "z".repeat(64)),
          // revision 为大写十六进制
          """
          {"sourceId": "%s", "name": "valid-name", "revision": "%s"}
          """
              .formatted(SOURCE_ID, "A".repeat(64))
        };

    for (String invalidJson : invalidCases) {
      EnvironmentCapabilityResult result = execute(capability, invalidJson);
      assertTrue(result.error(), "expected error for: " + invalidJson);
      assertEquals("Error: " + SkillLoadCapability.INVALID_REQUEST_MESSAGE, text(result));
    }
  }

  /** 测试意图：已发布的 Skill 能够被成功加载并返回符合 SkillLoadPayload 格式的 JSON。 */
  @Test
  void successfullyLoadsSkillBodyAndBaseDirectory() throws Exception {
    Path skillRoot = Files.createDirectories(tempDir.resolve("skills"));
    Path skillDir = Files.createDirectories(skillRoot.resolve("greeting"));
    Files.writeString(
        skillDir.resolve("SKILL.md"),
        "---\nname: greeting\ndescription: A greeting skill\n---\n# Greeting\nHello world!\n");

    DaemonSkillSourceConfig config =
        DaemonSkillSourceConfig.path(
            SOURCE_ID, 1, 1, skillRoot.toString(), false, Set.of(SOURCE_ID));
    DaemonSkillSourceSnapshot snapshot = registry.refresh(config);
    assertEquals(1, snapshot.skills().size());
    String revision = snapshot.skills().get(0).contentRevision();

    String arguments =
        """
        {
          "sourceId": "%s",
          "name": "greeting",
          "revision": "%s"
        }
        """
            .formatted(SOURCE_ID, revision);

    EnvironmentCapabilityResult result = execute(capability, arguments);
    assertFalse(result.error());
    JsonNode jsonNode = MAPPER.readTree(json(result));
    assertEquals("# Greeting\nHello world!", jsonNode.get("body").textValue());
    assertEquals(skillDir.toRealPath().toString(), jsonNode.get("baseDirectory").textValue());
  }

  /** 测试意图：正文 blob 超出传输上限导致编码失败时，worker 捕获 RuntimeException 并收敛为不透明的 LOAD_FAILED_MESSAGE。 */
  @Test
  void handlesOversizedBodyBlobAsLoadFailedMessage() throws Exception {
    Path skillRoot = Files.createDirectories(tempDir.resolve("skills-oversized"));
    Path skillDir = Files.createDirectories(skillRoot.resolve("valid-then-huge"));
    Files.writeString(
        skillDir.resolve("SKILL.md"),
        "---\nname: valid-then-huge\ndescription: test\n---\n# Small body\n");

    DaemonSkillSourceConfig config =
        DaemonSkillSourceConfig.path(
            SOURCE_ID, 1, 1, skillRoot.toString(), false, Set.of(SOURCE_ID));
    DaemonSkillSourceSnapshot snapshot = registry.refresh(config);
    String revision = snapshot.skills().get(0).contentRevision();

    // 篡改 blob 目录下的正文文件，使其超过 JsonResultContent 1 MiB 上限
    Path bodyFile = tempDir.resolve("data/skills/bodies/" + revision + ".md");
    Files.writeString(bodyFile, "x".repeat(JsonResultContent.MAX_JSON_UTF8_BYTES + 10));

    String arguments =
        """
        {
          "sourceId": "%s",
          "name": "valid-then-huge",
          "revision": "%s"
        }
        """
            .formatted(SOURCE_ID, revision);

    EnvironmentCapabilityResult result = execute(capability, arguments);
    assertTrue(result.error());
    assertEquals("Error: " + SkillLoadCapability.LOAD_FAILED_MESSAGE, text(result));
  }

  /** 测试意图：请求未保留的 revision 时返回带 RESOURCE_CHANGED 错误码的不透明错误，避免以新正文冒充旧版本。 */
  @Test
  void returnsResourceChangedWhenRevisionNotRetained() throws Exception {
    String arguments =
        """
        {
          "sourceId": "%s",
          "name": "greeting",
          "revision": "%s"
        }
        """
            .formatted(SOURCE_ID, "b".repeat(64));

    EnvironmentCapabilityResult result = execute(capability, arguments);
    assertTrue(result.error());
    assertEquals("Error: " + SkillLoadCapability.RESOURCE_CHANGED_MESSAGE, text(result));
    JsonNode details = MAPPER.readTree(result.detailsJson());
    assertEquals(
        EnvironmentCapabilityResultCodes.RESOURCE_CHANGED, details.get("code").textValue());
  }

  /** 测试意图：执行 handle 的 cancel 应当设置 isCancelled 为 true 并调用 listener 完成取消，后续取消操作应幂等。 */
  @Test
  void supportsCancellationOnExecutionHandle() throws Exception {
    CountDownLatch startedLatch = new CountDownLatch(1);
    CountDownLatch releaseLatch = new CountDownLatch(1);

    // 使用阻塞的 executor 模拟在途执行
    ExecutorService blockedExecutor = Executors.newSingleThreadExecutor();
    try {
      SkillLoadCapability blockableCapability = new SkillLoadCapability(registry, blockedExecutor);
      EnvironmentCapabilityExecutionRequest request =
          new EnvironmentCapabilityExecutionRequest(
              descriptor,
              new EnvironmentCapabilityCall(
                  "call-cancel",
                  """
                  {"sourceId": "%s", "name": "greeting", "revision": "%s"}
                  """
                      .formatted(SOURCE_ID, "a".repeat(64))),
              Duration.ofSeconds(10));

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

      RecordingListener listener = new RecordingListener();
      EnvironmentCapabilityExecutionHandle handle = blockableCapability.execute(request, listener);

      assertFalse(handle.isCancelled());
      handle.cancel();
      assertTrue(handle.isCancelled());

      // 重复 cancel 保持幂等
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

  private EnvironmentCapabilityResult execute(SkillLoadCapability cap, String argumentsJson)
      throws Exception {
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionRequest request =
        new EnvironmentCapabilityExecutionRequest(
            cap.descriptor(),
            new EnvironmentCapabilityCall("test-call", argumentsJson),
            Duration.ofSeconds(5));

    cap.execute(request, listener);
    assertTrue(listener.await(), "timed out waiting for execution");
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
}
