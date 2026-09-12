package fun.fengwk.kkstudio.platform.environment.directory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
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
import fun.fengwk.kkstudio.harness.environment.daemon.EnvironmentDirectoryEntry;
import fun.fengwk.kkstudio.harness.environment.daemon.EnvironmentDirectoryListing;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentDirectoryFailureCode;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentDirectoryListResult;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentDirectoryDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentDirectoryEntryDTO;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 本节点目录查询适配器：把 {@code FS_LIST_DIRECTORY} capability 结果映射为产品 DTO。
 *
 * <p>只做产品读模型映射与超时处理，不拥有任何会话状态；并发与终态语义由 {@link EnvironmentDaemonServer} 保证。
 */
@Component
public class LocalDirectoryExecutor implements LocalDirectoryQueryPort {

  private final EnvironmentDaemonServer server;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public LocalDirectoryExecutor(EnvironmentDaemonServer server) {
    this.server = Objects.requireNonNull(server, "server");
  }

  @Override
  public CompletableFuture<EnvironmentDirectoryListResult> executeLocalDirectoryList(
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

    EnvironmentCapabilityDescriptor descriptor =
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_LIST_DIRECTORY);
    ObjectNode argsNode = objectMapper.createObjectNode();
    argsNode.put("path", directoryPath);
    String callId = UUID.randomUUID().toString();
    EnvironmentCapabilityCall call = new EnvironmentCapabilityCall(callId, argsNode.toString());
    EnvironmentCapabilityExecutionRequest request =
        new EnvironmentCapabilityExecutionRequest(descriptor, call, timeout, null);
    EnvironmentBinding binding = new EnvironmentBinding(environmentId, ".");

    CompletableFuture<EnvironmentDirectoryListResult> future = new CompletableFuture<>();
    EnvironmentCapabilityExecutionHandle handle;
    try {
      handle =
          server.invoke(
              binding,
              request,
              new EnvironmentCapabilityExecutionListener() {
                @Override
                public void onPartial(EnvironmentCapabilityResult partial) {}

                @Override
                public void onComplete(EnvironmentCapabilityResult result) {
                  future.complete(toResult(result));
                }

                @Override
                public void onError(Throwable error) {
                  future.complete(unavailable(environmentId));
                }
              });
    } catch (RuntimeException error) {
      return CompletableFuture.completedFuture(unavailable(environmentId));
    }
    return future
        .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
        .exceptionally(
            error -> {
              // 超时或连接丢失：终结该 invocation（至多一次 CANCEL）并返回确定性失败码。
              server.expire(handle);
              boolean isTimeout =
                  error instanceof TimeoutException || error.getCause() instanceof TimeoutException;
              if (isTimeout) {
                return new EnvironmentDirectoryListResult.Failed(
                    EnvironmentDirectoryFailureCode.TIMEOUT,
                    "environment "
                        + environmentId
                        + " directory listing timed out after "
                        + timeout.toMillis()
                        + "ms");
              }
              return unavailable(environmentId);
            });
  }

  @Override
  public Set<EnvironmentId> localReadyEnvironments() {
    return server.readyEnvironments();
  }

  private static EnvironmentDirectoryListResult unavailable(EnvironmentId environmentId) {
    return new EnvironmentDirectoryListResult.Failed(
        EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE,
        "environment " + environmentId + " is not ready; directory listing is unavailable");
  }

  private EnvironmentDirectoryListResult toResult(EnvironmentCapabilityResult result) {
    if (result.error()) {
      String errorMsg = extractErrorMessage(result, "directory listing failed");
      return new EnvironmentDirectoryListResult.Failed(classifyDirectoryError(errorMsg), errorMsg);
    }
    if (result.contents().size() == 1
        && result.contents().get(0) instanceof JsonResultContent json) {
      try {
        return new EnvironmentDirectoryListResult.Loaded(
            toDto(objectMapper.readValue(json.json(), EnvironmentDirectoryListing.class)));
      } catch (Exception parseError) {
        return new EnvironmentDirectoryListResult.Failed(
            EnvironmentDirectoryFailureCode.IO_ERROR,
            "Failed to parse directory listing: " + parseError.getMessage());
      }
    }
    return new EnvironmentDirectoryListResult.Failed(
        EnvironmentDirectoryFailureCode.IO_ERROR,
        "Expected JsonResultContent but got unexpected content");
  }

  private static EnvironmentDirectoryDTO toDto(EnvironmentDirectoryListing listing) {
    EnvironmentDirectoryDTO dto = new EnvironmentDirectoryDTO();
    dto.setPath(listing.path());
    dto.setDisplayPath(listing.displayPath());
    dto.setParentPath(listing.parentPath());
    dto.setTruncated(listing.truncated());
    dto.setGitBranch(listing.gitBranch());
    List<EnvironmentDirectoryEntryDTO> entries = new ArrayList<>();
    for (EnvironmentDirectoryEntry entry : listing.entries()) {
      EnvironmentDirectoryEntryDTO entryDto = new EnvironmentDirectoryEntryDTO();
      entryDto.setName(entry.name());
      entryDto.setPath(entry.path());
      entries.add(entryDto);
    }
    dto.setEntries(List.copyOf(entries));
    return dto;
  }

  private static EnvironmentDirectoryFailureCode classifyDirectoryError(String message) {
    if (message == null) {
      return EnvironmentDirectoryFailureCode.IO_ERROR;
    }
    String lower = message.toLowerCase();
    if (lower.contains("nosuchfile")
        || lower.contains("not found")
        || lower.contains("does not exist")) {
      return EnvironmentDirectoryFailureCode.NOT_FOUND;
    }
    if (lower.contains("notdirectory") || lower.contains("not a directory")) {
      return EnvironmentDirectoryFailureCode.NOT_DIRECTORY;
    }
    if (lower.contains("invalid") || lower.contains("escapes") || lower.contains("canonical")) {
      return EnvironmentDirectoryFailureCode.INVALID_PATH;
    }
    return EnvironmentDirectoryFailureCode.IO_ERROR;
  }

  private static String extractErrorMessage(EnvironmentCapabilityResult result, String fallback) {
    if (!result.contents().isEmpty()
        && result.contents().get(0) instanceof TextResultContent text) {
      String msg = text.text();
      if (msg.startsWith("Error: ")) {
        return msg.substring("Error: ".length());
      }
      return msg;
    }
    return fallback;
  }

  private static void requirePositive(Duration timeout) {
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
  }
}
