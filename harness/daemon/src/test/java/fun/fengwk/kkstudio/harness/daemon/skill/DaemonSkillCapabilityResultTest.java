package fun.fengwk.kkstudio.harness.daemon.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResultCodes;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceConfig;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceConfigCodec;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 管理能力与 {@code skill.load} 的结果契约：失败文本恒为不透明常量，绝不携带本地事实。
 *
 * <p>测试意图：把"注入敏感外观的输入值"与"最终结果文本"直接对照，证明路径、URL、ref、argv、异常消息与正文都不会进入错误结果；同时验证 {@code skill.load}
 * 的已变化 revision 仍通过结构化 {@code RESOURCE_CHANGED} 码表达。
 */
class DaemonSkillCapabilityResultTest {

  @TempDir Path tempDir;

  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  @AfterEach
  void closeExecutor() {
    executor.shutdownNow();
  }

  /** 不存在的 PATH 来源只返回固定失败文本：本地路径、异常消息与异常类都不得外泄。 */
  @Test
  void pathSourceFailureIsOpaque() throws Exception {
    String sensitivePath = tempDir.resolve("secret-token-skills").toString();
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(tempDir.resolve("data"), tempDir);
    EnvironmentCapabilityResult result =
        run(
            new DaemonSkillSourceCapability(
                DaemonSkillSourceCapability.Operation.REFRESH, registry, executor),
            pathSourceArguments(sensitivePath));

    assertTrue(result.error());
    assertOpaque(result, sensitivePath);
    assertEquals(
        DaemonSkillSourceCapability.OPERATION_FAILED_MESSAGE,
        text(result).substring("Error: ".length()));
    // PATH 目录不存在不是发布失败以外的语义：registry 保持空目录。
    assertTrue(registry.snapshots().isEmpty());
  }

  /** GIT 来源失败同样只返回固定文本：URL（可能含凭据）与 ref 都不得进入结果。 */
  @Test
  void gitSourceFailureIsOpaque() throws Exception {
    String sensitiveUrl = "https://user:secret-token@git.example/repo.git";
    String sensitiveRef = "refs/heads/secret-token-branch";
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(tempDir.resolve("data"), tempDir);
    DaemonSkillSourceConfigCodec codec = new DaemonSkillSourceConfigCodec();
    String arguments =
        codec.encode(
            DaemonSkillSourceConfig.git(
                DaemonSkillTestSupport.SOURCE_ID,
                1,
                1,
                sensitiveUrl,
                sensitiveRef,
                null,
                null,
                Set.of(DaemonSkillTestSupport.SOURCE_ID)));

    EnvironmentCapabilityResult result =
        run(
            new DaemonSkillSourceCapability(
                DaemonSkillSourceCapability.Operation.INSTALL, registry, executor),
            arguments);

    assertTrue(result.error());
    assertOpaque(result, sensitiveUrl);
    assertOpaque(result, sensitiveRef);
    assertOpaque(result, "secret-token");
  }

  /**
   * 请求本身非法时使用自己的固定文本：既不回显字段值，也不与操作失败混淆。
   *
   * <p>这里用 schema 允许、但语义自相矛盾的配置（GIT 来源携带 {@code path}）触发能力内部的解码失败，从而进入 {@code
   * INVALID_REQUEST_MESSAGE} 路径，而不是在能力之外被 schema 提前拒绝。
   */
  @Test
  void invalidRequestHasItsOwnFixedMessage() throws Exception {
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(tempDir.resolve("data"), tempDir);
    String sensitiveValue = "/srv/secret-token/skills";

    EnvironmentCapabilityResult result =
        run(
            new DaemonSkillSourceCapability(
                DaemonSkillSourceCapability.Operation.REFRESH, registry, executor),
            "{\"sourceId\":\""
                + DaemonSkillTestSupport.SOURCE_ID
                + "\",\"sourceVersion\":1,\"sourceSetVersion\":1,\"type\":\"git\","
                + "\"path\":\""
                + sensitiveValue
                + "\",\"url\":\"https://git.example/repo.git\",\"activeSourceIds\":[\""
                + DaemonSkillTestSupport.SOURCE_ID
                + "\"]}");

    assertTrue(result.error());
    assertEquals(
        DaemonSkillSourceCapability.INVALID_REQUEST_MESSAGE,
        text(result).substring("Error: ".length()));
    assertOpaque(result, sensitiveValue);
  }

  /** 请求的冻结 revision 不再保留时：错误文本固定可读，details 仍是结构化 {@code RESOURCE_CHANGED}。 */
  @Test
  void unavailableSkillRevisionIsStructuredAndOpaque() throws Exception {
    DaemonSkillRegistry registry =
        DaemonSkillTestSupport.publish(tempDir.resolve("data"), tempDir, 1).registry();
    String missingRevision = "0".repeat(64);

    EnvironmentCapabilityResult result =
        run(
            new SkillLoadCapability(registry, executor),
            "{\"sourceId\":\""
                + DaemonSkillTestSupport.SOURCE_ID
                + "\",\"name\":\"absent\",\"revision\":\""
                + missingRevision
                + "\"}");

    assertTrue(result.error());
    assertEquals(
        "{\"code\":\"" + EnvironmentCapabilityResultCodes.RESOURCE_CHANGED + "\"}",
        result.detailsJson());
    assertEquals(
        SkillLoadCapability.RESOURCE_CHANGED_MESSAGE, text(result).substring("Error: ".length()));
    assertOpaque(result, missingRevision);
    assertOpaque(result, tempDir.toString());
  }

  /** {@code skill.load} 的非法身份参数使用自己的固定文本，且不泄露请求值。 */
  @Test
  void invalidSkillLoadRequestIsOpaque() throws Exception {
    String sensitiveRevision = "/srv/secret-token/body";
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(tempDir.resolve("data"), tempDir);

    EnvironmentCapabilityResult result =
        run(
            new SkillLoadCapability(registry, executor),
            "{\"sourceId\":\""
                + DaemonSkillTestSupport.SOURCE_ID
                + "\",\"name\":\"dev\",\"revision\":\""
                + sensitiveRevision
                + "\"}");

    // revision 形状非法属于请求错误，不得伪装成一个合法但已淘汰的资源版本。
    assertTrue(result.error());
    assertEquals("{}", result.detailsJson());
    assertEquals(
        SkillLoadCapability.INVALID_REQUEST_MESSAGE, text(result).substring("Error: ".length()));
    assertOpaque(result, sensitiveRevision);
  }

  private EnvironmentCapabilityResult run(EnvironmentCapability capability, String argumentsJson)
      throws Exception {
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionRequest request =
        new EnvironmentCapabilityExecutionRequest(
            capability.descriptor(),
            new EnvironmentCapabilityCall("call-1", argumentsJson),
            Duration.ofSeconds(10));
    EnvironmentCapabilityExecutionHandle handle = capability.execute(request, listener);
    assertNotNull(handle);
    assertTrue(listener.await(), "capability execution did not complete");
    assertNotNull(listener.result);
    return listener.result;
  }

  /** 合法的 PATH 来源 arguments：与共享 schema 的 required 集合一致。 */
  private String pathSourceArguments(String path) {
    return new DaemonSkillSourceConfigCodec()
        .encode(
            DaemonSkillSourceConfig.path(
                DaemonSkillTestSupport.SOURCE_ID,
                1,
                1,
                path,
                false,
                Set.of(DaemonSkillTestSupport.SOURCE_ID)));
  }

  /** 错误结果的所有文本通道都不得包含给定敏感值。 */
  private static void assertOpaque(EnvironmentCapabilityResult result, String sensitiveValue) {
    assertFalse(result.detailsJson().contains(sensitiveValue), result.detailsJson());
    for (ResultContent content : result.contents()) {
      if (content instanceof TextResultContent text) {
        assertFalse(text.text().contains(sensitiveValue), text.text());
      }
    }
  }

  private static String text(EnvironmentCapabilityResult result) {
    return result.contents().stream()
        .filter(TextResultContent.class::isInstance)
        .map(TextResultContent.class::cast)
        .map(TextResultContent::text)
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

    private boolean await() throws InterruptedException {
      return completed.await(10, TimeUnit.SECONDS);
    }
  }
}
