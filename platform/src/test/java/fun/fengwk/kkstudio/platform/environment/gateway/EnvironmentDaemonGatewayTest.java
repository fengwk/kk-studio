package fun.fengwk.kkstudio.platform.environment.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.environment.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.platform.environment.directory.LocalDirectoryQueryPort;
import fun.fengwk.kkstudio.platform.environment.query.EnvironmentQueryCoordinator;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentStatus;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentDirectoryFailureCode;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentDirectoryListResult;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentSkillLoadResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * {@link EnvironmentDaemonGateway} 产品适配契约测试。
 *
 * <p>会话核心、租约注册表、本地目录端口与跨节点协调器全部以 mock 表达窄端口语义：本测试只验证 adapter 的请求校验、结果映射与路由 选择，不重复覆盖会话状态机（那是 {@code
 * harness/environment-server} 的职责）。每个用例在调用后通过 {@link #lastListener()} 驱动真实结果分支。
 */
class EnvironmentDaemonGatewayTest {

  private static final EnvironmentId ENVIRONMENT_ID =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final EnvironmentDaemonServer server = mock(EnvironmentDaemonServer.class);
  private final EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
  private final LocalDirectoryQueryPort localDirectoryQueryPort =
      mock(LocalDirectoryQueryPort.class);
  private final EnvironmentQueryCoordinator queryCoordinator =
      mock(EnvironmentQueryCoordinator.class);

  private volatile EnvironmentCapabilityExecutionListener capturedListener;

  /** 测试意图：daemon 返回单段文本时映射为 Loaded 正文，且 INVOKE 使用 skill.load 能力且不带 workdir。 */
  @Test
  void loadSkillMapsSingleTextContentToLoadedBody() {
    EnvironmentDaemonGateway gateway = gateway(queryCoordinator);
    stubServerReturningHandle();

    CompletableFuture<EnvironmentSkillLoadResult> future =
        gateway.loadSkill(ENVIRONMENT_ID, "demo-skill", TIMEOUT);
    lastListener().onComplete(EnvironmentCapabilityResult.text("call", "# skill body"));

    EnvironmentSkillLoadResult.Loaded loaded =
        assertInstanceOf(EnvironmentSkillLoadResult.Loaded.class, future.join());
    assertEquals("demo-skill", loaded.skillName());
    assertEquals("# skill body", loaded.content());

    ArgumentCaptor<EnvironmentCapabilityExecutionRequest> request =
        ArgumentCaptor.forClass(EnvironmentCapabilityExecutionRequest.class);
    ArgumentCaptor<EnvironmentBinding> binding = ArgumentCaptor.forClass(EnvironmentBinding.class);
    verify(server).invoke(binding.capture(), request.capture(), any());
    assertEquals("skill.load", request.getValue().descriptor().id().value());
    assertNull(request.getValue().workdir());
    assertEquals(ENVIRONMENT_ID, binding.getValue().environmentId());
  }

  /** 测试意图：daemon 错误结果剥掉统一的 {@code Error: } 前缀后作为失败消息透传。 */
  @Test
  void loadSkillStripsErrorPrefixFromDaemonFailure() {
    EnvironmentDaemonGateway gateway = gateway(queryCoordinator);
    stubServerReturningHandle();

    CompletableFuture<EnvironmentSkillLoadResult> future =
        gateway.loadSkill(ENVIRONMENT_ID, "missing", TIMEOUT);
    lastListener().onComplete(EnvironmentCapabilityResult.error("call", "unknown skill: missing"));

    EnvironmentSkillLoadResult.Failed failed =
        assertInstanceOf(EnvironmentSkillLoadResult.Failed.class, future.join());
    assertEquals("unknown skill: missing", failed.message());
  }

  /** 测试意图：错误结果无内容时回退到默认提示，内容类型非文本时按契约失败。 */
  @Test
  void loadSkillRejectsUnexpectedDaemonPayloads() {
    EnvironmentDaemonGateway gateway = gateway(queryCoordinator);
    stubServerReturningHandle();

    CompletableFuture<EnvironmentSkillLoadResult> empty =
        gateway.loadSkill(ENVIRONMENT_ID, "demo", TIMEOUT);
    lastListener().onComplete(new EnvironmentCapabilityResult("call", List.of(), true, "{}"));
    assertEquals(
        "unknown skill: demo",
        assertInstanceOf(EnvironmentSkillLoadResult.Failed.class, empty.join()).message());

    CompletableFuture<EnvironmentSkillLoadResult> json =
        gateway.loadSkill(ENVIRONMENT_ID, "demo", TIMEOUT);
    lastListener()
        .onComplete(
            new EnvironmentCapabilityResult(
                "call", List.of(new JsonResultContent("{}")), false, "{}"));
    assertEquals(
        "Unexpected skill content type for demo",
        assertInstanceOf(EnvironmentSkillLoadResult.Failed.class, json.join()).message());
  }

  /** 测试意图：连接丢失（onError）与发送前不可用/不确定异常都收敛为同一离线文案，不向调用方抛出。 */
  @Test
  void loadSkillMapsConnectionLossAndPreSendFailuresToOffline() {
    EnvironmentDaemonGateway gateway = gateway(queryCoordinator);
    stubServerReturningHandle();
    CompletableFuture<EnvironmentSkillLoadResult> lost =
        gateway.loadSkill(ENVIRONMENT_ID, "demo", TIMEOUT);
    lastListener().onError(new IllegalStateException("connection lost"));
    assertOffline(assertInstanceOf(EnvironmentSkillLoadResult.Failed.class, lost.join()).message());

    stubServerThrowing(new EnvironmentCapabilityUnavailableException("gone"));
    assertOffline(
        assertInstanceOf(
                EnvironmentSkillLoadResult.Failed.class,
                gateway.loadSkill(ENVIRONMENT_ID, "demo", TIMEOUT).join())
            .message());

    stubServerThrowing(new EnvironmentCapabilitySendUncertainException("uncertain"));
    assertOffline(
        assertInstanceOf(
                EnvironmentSkillLoadResult.Failed.class,
                gateway.loadSkill(ENVIRONMENT_ID, "demo", TIMEOUT).join())
            .message());
  }

  /** 测试意图：其他运行时异常保留原始消息，空消息回退到固定文案。 */
  @Test
  void loadSkillKeepsUnclassifiedFailureMessages() {
    EnvironmentDaemonGateway gateway = gateway(queryCoordinator);

    stubServerThrowing(new IllegalArgumentException("bad descriptor"));
    assertEquals(
        "bad descriptor",
        assertInstanceOf(
                EnvironmentSkillLoadResult.Failed.class,
                gateway.loadSkill(ENVIRONMENT_ID, "demo", TIMEOUT).join())
            .message());

    stubServerThrowing(new IllegalStateException());
    assertEquals(
        "skill load failed",
        assertInstanceOf(
                EnvironmentSkillLoadResult.Failed.class,
                gateway.loadSkill(ENVIRONMENT_ID, "demo", TIMEOUT).join())
            .message());
  }

  /** 测试意图：调用方 deadline 到期时终结该 invocation（至多一次 expire）并返回离线结果，不泄漏在途句柄。 */
  @Test
  void loadSkillExpiresInvocationOnTimeout() throws Exception {
    EnvironmentDaemonGateway gateway = gateway(queryCoordinator);
    EnvironmentCapabilityExecutionHandle handle = mock(EnvironmentCapabilityExecutionHandle.class);
    stubServerReturningHandle(handle);

    EnvironmentSkillLoadResult.Failed failed =
        assertInstanceOf(
            EnvironmentSkillLoadResult.Failed.class,
            gateway
                .loadSkill(ENVIRONMENT_ID, "demo", Duration.ofMillis(30))
                .get(5, TimeUnit.SECONDS));

    assertOffline(failed.message());
    verify(server).expire(handle);
  }

  /** 测试意图：非法入参在发起调用前失败。 */
  @Test
  void loadSkillValidatesArgumentsBeforeInvoking() {
    EnvironmentDaemonGateway gateway = gateway(queryCoordinator);
    assertThrows(NullPointerException.class, () -> gateway.loadSkill(null, "demo", TIMEOUT));
    assertThrows(
        IllegalArgumentException.class, () -> gateway.loadSkill(ENVIRONMENT_ID, " ", TIMEOUT));
    assertThrows(
        IllegalArgumentException.class,
        () -> gateway.loadSkill(ENVIRONMENT_ID, "demo", Duration.ZERO));
    assertThrows(NullPointerException.class, () -> gateway.listDirectory(null, ".", TIMEOUT));
  }

  /** 测试意图：非法目录路径在本地判定为 INVALID_PATH，不触达 registry 或 wire。 */
  @Test
  void listDirectoryRejectsNonCanonicalPathsLocally() {
    EnvironmentDaemonGateway gateway = gateway(queryCoordinator);

    EnvironmentDirectoryListResult.Failed failed =
        assertInstanceOf(
            EnvironmentDirectoryListResult.Failed.class,
            gateway.listDirectory(ENVIRONMENT_ID, "/absolute", TIMEOUT).join());

    assertEquals(EnvironmentDirectoryFailureCode.INVALID_PATH, failed.code());
  }

  /** 测试意图：未注册环境立即以 ENVIRONMENT_NOT_FOUND 结束，不进入 mailbox。 */
  @Test
  void listDirectoryFailsFastWhenEnvironmentIsUnknown() {
    EnvironmentDaemonGateway gateway = gateway(queryCoordinator);
    when(registry.find(ENVIRONMENT_ID)).thenReturn(Optional.empty());

    EnvironmentDirectoryListResult.Failed failed =
        assertInstanceOf(
            EnvironmentDirectoryListResult.Failed.class,
            gateway.listDirectory(ENVIRONMENT_ID, ".", TIMEOUT).join());

    assertEquals(EnvironmentDirectoryFailureCode.ENVIRONMENT_NOT_FOUND, failed.code());
  }

  /** 测试意图：集群内没有未过期的 READY 路由时立即不可用，绝不降级为信箱等待。 */
  @Test
  void listDirectoryFailsFastWhenNoReadyRouteExists() {
    EnvironmentDaemonGateway gateway = gateway(queryCoordinator);
    when(registry.find(ENVIRONMENT_ID)).thenReturn(Optional.of(connection()));
    when(registry.hasReadyLease(ENVIRONMENT_ID)).thenReturn(false);

    EnvironmentDirectoryListResult.Failed failed =
        assertInstanceOf(
            EnvironmentDirectoryListResult.Failed.class,
            gateway.listDirectory(ENVIRONMENT_ID, ".", TIMEOUT).join());

    assertEquals(EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE, failed.code());
  }

  /** 测试意图：registry 不可用时以确定性的 ENVIRONMENT_UNAVAILABLE 收敛，不抛出 DB 异常。 */
  @Test
  void listDirectoryMapsRegistryFailureToUnavailable() {
    EnvironmentDaemonGateway gateway = gateway(queryCoordinator);
    when(registry.find(ENVIRONMENT_ID)).thenThrow(new DataAccessResourceFailureException("db"));

    EnvironmentDirectoryListResult.Failed failed =
        assertInstanceOf(
            EnvironmentDirectoryListResult.Failed.class,
            gateway.listDirectory(ENVIRONMENT_ID, ".", TIMEOUT).join());

    assertEquals(EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE, failed.code());
  }

  /** 测试意图：本节点持有 READY 租约时走本地端口，绝不重复投递到跨节点 mailbox。 */
  @Test
  void listDirectoryRoutesToLocalPortWhenLeaseIsHeldLocally() {
    EnvironmentDaemonGateway gateway = gateway(queryCoordinator);
    when(registry.find(ENVIRONMENT_ID)).thenReturn(Optional.of(connection()));
    when(registry.hasReadyLease(ENVIRONMENT_ID)).thenReturn(true);
    when(server.holdsReadyLease(ENVIRONMENT_ID)).thenReturn(true);
    EnvironmentDirectoryListResult expected =
        new EnvironmentDirectoryListResult.Failed(
            EnvironmentDirectoryFailureCode.IO_ERROR, "local");
    when(localDirectoryQueryPort.executeLocalDirectoryList(ENVIRONMENT_ID, ".", TIMEOUT))
        .thenReturn(CompletableFuture.completedFuture(expected));

    assertEquals(expected, gateway.listDirectory(ENVIRONMENT_ID, ".", TIMEOUT).join());
  }

  /** 测试意图：本节点不是 route owner 时把查询交给跨节点协调器。 */
  @Test
  void listDirectoryRoutesToCoordinatorWhenLeaseIsHeldElsewhere() {
    EnvironmentDaemonGateway gateway = gateway(queryCoordinator);
    when(registry.find(ENVIRONMENT_ID)).thenReturn(Optional.of(connection()));
    when(registry.hasReadyLease(ENVIRONMENT_ID)).thenReturn(true);
    when(server.holdsReadyLease(ENVIRONMENT_ID)).thenReturn(false);
    EnvironmentDirectoryListResult expected =
        new EnvironmentDirectoryListResult.Failed(
            EnvironmentDirectoryFailureCode.TIMEOUT, "remote");
    when(queryCoordinator.executeRemoteDirectoryQuery(ENVIRONMENT_ID, ".", TIMEOUT))
        .thenReturn(CompletableFuture.completedFuture(expected));

    assertEquals(expected, gateway.listDirectory(ENVIRONMENT_ID, ".", TIMEOUT).join());
  }

  /** 测试意图：没有跨节点协调器时（单节点部署）非 owner 路径确定不可用，而不是静默挂起。 */
  @Test
  void listDirectoryFailsWhenRemoteCoordinationIsNotWired() {
    EnvironmentDaemonGateway gateway = gateway(null);
    when(registry.find(ENVIRONMENT_ID)).thenReturn(Optional.of(connection()));
    when(registry.hasReadyLease(ENVIRONMENT_ID)).thenReturn(true);
    when(server.holdsReadyLease(ENVIRONMENT_ID)).thenReturn(false);

    EnvironmentDirectoryListResult.Failed failed =
        assertInstanceOf(
            EnvironmentDirectoryListResult.Failed.class,
            gateway.listDirectory(ENVIRONMENT_ID, ".", TIMEOUT).join());

    assertEquals(EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE, failed.code());
  }

  private EnvironmentDaemonGateway gateway(EnvironmentQueryCoordinator coordinator) {
    return new EnvironmentDaemonGateway(server, registry, localDirectoryQueryPort, coordinator);
  }

  private void stubServerReturningHandle() {
    stubServerReturningHandle(mock(EnvironmentCapabilityExecutionHandle.class));
  }

  /**
   * 用 {@code doAnswer} 形式安装 stub：先注册 stub 再调用 mock，避免 {@code when(mock.invoke(...))} 在装配 stub 期间就进入
   * 目标方法语义（会话核心是 final 类，只有 inline mock maker 能拦截）。
   */
  private void stubServerReturningHandle(EnvironmentCapabilityExecutionHandle handle) {
    doAnswer(
            invocation -> {
              capturedListener = invocation.getArgument(2);
              return handle;
            })
        .when(server)
        .invoke(any(EnvironmentBinding.class), any(), any());
  }

  private void stubServerThrowing(RuntimeException error) {
    doThrow(error).when(server).invoke(any(EnvironmentBinding.class), any(), any());
  }

  private EnvironmentCapabilityExecutionListener lastListener() {
    return Objects.requireNonNull(capturedListener, "server.invoke was never called");
  }

  private static void assertOffline(String message) {
    assertTrue(
        message.contains("is offline"), () -> "expected offline message but was: " + message);
  }

  private static EnvironmentConnection connection() {
    return new EnvironmentConnection(
        ENVIRONMENT_ID,
        UUID.randomUUID(),
        UUID.randomUUID(),
        LiveEnvironmentStatus.CONNECTING,
        null,
        Instant.now(),
        Instant.now().plusSeconds(60));
  }
}
