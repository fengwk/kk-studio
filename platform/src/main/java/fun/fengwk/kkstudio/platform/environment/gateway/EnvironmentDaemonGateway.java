package fun.fengwk.kkstudio.platform.environment.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.EnvironmentWorkspacePath;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.platform.environment.directory.LocalDirectoryQueryPort;
import fun.fengwk.kkstudio.platform.environment.query.EnvironmentQueryCoordinator;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentDirectoryFailureCode;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentDirectoryListResult;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentDirectoryLister;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentSkillLoadResult;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentSkillLoader;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Environment daemon 的 Platform 产品适配器：把 skill 与目录调用桥接到纯 Java 会话核心 {@link
 * EnvironmentDaemonServer}，并映射为平台 DTO。
 *
 * <p>本类不拥有 HELLO/READY/lease/sequence/invocation 状态，也不实现 transport 端点（WebSocket 帧由核心的 {@code
 * DaemonEndpoint} 直接接收），不限制同一 Environment 的并发调用数。跨节点目录协调按端口注入，不存在运行期可变注册。
 */
@Service
public class EnvironmentDaemonGateway
    implements EnvironmentSkillLoader, EnvironmentDirectoryLister {

  private final EnvironmentDaemonServer server;
  private final LocalDirectoryQueryPort localDirectoryQueryPort;
  private final EnvironmentRegistry environmentRegistry;
  private final EnvironmentQueryCoordinator directoryQueryCoordinator;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public EnvironmentDaemonGateway(
      EnvironmentDaemonServer server,
      EnvironmentRegistry environmentRegistry,
      LocalDirectoryQueryPort localDirectoryQueryPort,
      @Autowired(required = false) EnvironmentQueryCoordinator directoryQueryCoordinator) {
    this.server = Objects.requireNonNull(server, "server");
    this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry");
    this.localDirectoryQueryPort =
        Objects.requireNonNull(localDirectoryQueryPort, "localDirectoryQueryPort");
    this.directoryQueryCoordinator = directoryQueryCoordinator;
  }

  @Override
  public CompletableFuture<EnvironmentSkillLoadResult> loadSkill(
      EnvironmentId environmentId, String skillName, Duration timeout) {
    Objects.requireNonNull(environmentId, "environmentId");
    String skill = requireNonBlank(skillName, "skillName");
    requirePositive(timeout);

    EnvironmentCapabilityDescriptor descriptor =
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.SKILL_LOAD);
    ObjectNode argsNode = objectMapper.createObjectNode();
    argsNode.put("name", skill);
    EnvironmentCapabilityCall call =
        new EnvironmentCapabilityCall(UUID.randomUUID().toString(), argsNode.toString());
    EnvironmentCapabilityExecutionRequest request =
        new EnvironmentCapabilityExecutionRequest(descriptor, call, timeout, null);

    CompletableFuture<EnvironmentSkillLoadResult> future = new CompletableFuture<>();
    EnvironmentCapabilityExecutionHandle handle;
    try {
      handle =
          server.invoke(
              new EnvironmentBinding(environmentId, "."),
              request,
              new EnvironmentCapabilityExecutionListener() {
                @Override
                public void onPartial(EnvironmentCapabilityResult partial) {}

                @Override
                public void onComplete(EnvironmentCapabilityResult result) {
                  future.complete(toSkillResult(skill, result));
                }

                @Override
                public void onError(Throwable error) {
                  future.complete(offline(skill, environmentId));
                }
              });
    } catch (RuntimeException error) {
      return CompletableFuture.completedFuture(failed(skill, environmentId, error));
    }
    return future
        .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
        .exceptionally(
            error -> {
              server.expire(handle);
              return offline(skill, environmentId);
            });
  }

  @Override
  public CompletableFuture<EnvironmentDirectoryListResult> listDirectory(
      EnvironmentId environmentId, String path, Duration timeout) {
    Objects.requireNonNull(environmentId, "environmentId");
    String directoryPath;
    try {
      directoryPath = EnvironmentWorkspacePath.requireCanonicalRelativePath(path);
    } catch (IllegalArgumentException error) {
      return CompletableFuture.completedFuture(
          new EnvironmentDirectoryListResult.Failed(
              EnvironmentDirectoryFailureCode.INVALID_PATH, error.getMessage()));
    }
    requirePositive(timeout);

    EnvironmentConnection live;
    boolean hasReadyRoute;
    try {
      live = environmentRegistry.find(environmentId).orElse(null);
      hasReadyRoute = live != null && environmentRegistry.hasReadyLease(environmentId);
    } catch (DataAccessException error) {
      return CompletableFuture.completedFuture(
          new EnvironmentDirectoryListResult.Failed(
              EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE,
              "database unavailable: " + error.getMessage()));
    }
    if (live == null) {
      return CompletableFuture.completedFuture(
          new EnvironmentDirectoryListResult.Failed(
              EnvironmentDirectoryFailureCode.ENVIRONMENT_NOT_FOUND,
              "environment is not registered: " + environmentId));
    }
    if (!hasReadyRoute) {
      // 集群内不存在未过期的 READY 路由：立即失败，绝不降级为信箱等待超时。
      return CompletableFuture.completedFuture(unavailable(environmentId));
    }
    if (server.holdsReadyLease(environmentId)) {
      return localDirectoryQueryPort.executeLocalDirectoryList(
          environmentId, directoryPath, timeout);
    }
    if (directoryQueryCoordinator != null) {
      return directoryQueryCoordinator.executeRemoteDirectoryQuery(
          environmentId, directoryPath, timeout);
    }
    return CompletableFuture.completedFuture(unavailable(environmentId));
  }

  private static EnvironmentDirectoryListResult unavailable(EnvironmentId environmentId) {
    return new EnvironmentDirectoryListResult.Failed(
        EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE,
        "environment " + environmentId + " is not ready; directory listing is unavailable");
  }

  private static EnvironmentSkillLoadResult toSkillResult(
      String skill, EnvironmentCapabilityResult result) {
    if (result.error()) {
      return new EnvironmentSkillLoadResult.Failed(
          skill, extractErrorMessage(result, "unknown skill: " + skill));
    }
    if (result.contents().size() == 1
        && result.contents().get(0) instanceof TextResultContent text) {
      return new EnvironmentSkillLoadResult.Loaded(skill, text.text());
    }
    return new EnvironmentSkillLoadResult.Failed(
        skill, "Unexpected skill content type for " + skill);
  }

  private static String extractErrorMessage(EnvironmentCapabilityResult result, String fallback) {
    if (!result.contents().isEmpty()
        && result.contents().get(0) instanceof TextResultContent text) {
      String message = text.text();
      return message.startsWith("Error: ") ? message.substring("Error: ".length()) : message;
    }
    return fallback;
  }

  private static EnvironmentSkillLoadResult offline(String skill, EnvironmentId environmentId) {
    return new EnvironmentSkillLoadResult.Failed(
        skill, environmentId + " is offline; " + skill + " is unavailable");
  }

  private static EnvironmentSkillLoadResult failed(
      String skill, EnvironmentId environmentId, RuntimeException error) {
    if (error instanceof EnvironmentCapabilityUnavailableException
        || error instanceof EnvironmentCapabilitySendUncertainException) {
      return offline(skill, environmentId);
    }
    String message = error.getMessage();
    return new EnvironmentSkillLoadResult.Failed(
        skill, message == null || message.isBlank() ? "skill load failed" : message);
  }

  private static void requirePositive(Duration timeout) {
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
