package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.code.HttpStatus;
import fun.fengwk.convention4j.api.code.ImmutableResolvedConventionErrorCode;
import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.platform.ai.environment.service.EnvironmentDirectoryListResult;
import fun.fengwk.kkstudio.platform.ai.environment.service.EnvironmentDirectoryLister;
import fun.fengwk.kkstudio.platform.systemsettings.SystemSettingsSnapshot;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Environment Root 单层目录浏览 API（control-plane 只读，不走 Tool Invocation/Permission）。
 *
 * <p>HTTP 映射（{@code errorCode.code} 与应用结果分类同名）：环境未知（{@code ENVIRONMENT_NOT_FOUND}）与路径不存在（{@code
 * NOT_FOUND}）→ 404；环境已注册但未 READY/连接不可用（{@code ENVIRONMENT_UNAVAILABLE}）→ 409；非法路径（{@code
 * INVALID_PATH}/{@code NOT_DIRECTORY}）→ 400；daemon 往返超时（{@code TIMEOUT}）→ 504；daemon 本地 IO
 * 失败（{@code IO_ERROR}）→ 502。
 */
@RestController
public class StudioEnvironmentDirectoryController {

  private final EnvironmentDirectoryLister directoryLister;
  private final SystemSettingsSnapshot snapshot;

  public StudioEnvironmentDirectoryController(
      EnvironmentDirectoryLister directoryLister, SystemSettingsSnapshot snapshot) {
    this.directoryLister = Objects.requireNonNull(directoryLister, "directoryLister");
    this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
  }

  /**
   * 浏览 Environment Root 下单层目录；{@code path} 缺省为 {@code '.'}（root），wire 使用 {@code '/'} 分隔的 canonical
   * 相对路径。
   */
  @GetMapping("/api/ai/environments/{name}/directories")
  public ResponseEntity<Result<?>> listDirectories(
      @PathVariable String name, @RequestParam(defaultValue = ".") String path) {
    EnvironmentName environmentName;
    try {
      environmentName = new EnvironmentName(name);
    } catch (IllegalArgumentException error) {
      return errorResponse(HttpStatus.BAD_REQUEST, "INVALID_ENVIRONMENT_NAME", error.getMessage());
    }
    // gateway 的 orTimeout 保证 future 在配置超时内完成；blocking join 不会悬挂。
    CompletableFuture<EnvironmentDirectoryListResult> future =
        directoryLister.listDirectory(
            environmentName,
            path,
            Duration.ofMillis(snapshot.get().environment().directoryListTimeoutMillis()));
    EnvironmentDirectoryListResult result = future.join();
    if (result instanceof EnvironmentDirectoryListResult.Loaded loaded) {
      return ResponseEntity.ok(Results.ok(loaded.listing()));
    }
    EnvironmentDirectoryListResult.Failed failed = (EnvironmentDirectoryListResult.Failed) result;
    return switch (failed.code()) {
      case INVALID_PATH, NOT_DIRECTORY -> errorResponse(
          HttpStatus.BAD_REQUEST, failed.code().name(), failed.message());
      case ENVIRONMENT_NOT_FOUND, NOT_FOUND -> errorResponse(
          HttpStatus.NOT_FOUND, failed.code().name(), failed.message());
      case ENVIRONMENT_UNAVAILABLE -> errorResponse(
          HttpStatus.CONFLICT, failed.code().name(), failed.message());
      case TIMEOUT -> errorResponse(
          HttpStatus.GATEWAY_TIMEOUT, failed.code().name(), failed.message());
      case IO_ERROR -> errorResponse(
          HttpStatus.BAD_GATEWAY, failed.code().name(), failed.message());
    };
  }

  private static ResponseEntity<Result<?>> errorResponse(
      HttpStatus status, String code, String message) {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("detail", message);
    ImmutableResolvedConventionErrorCode errorCode =
        new ImmutableResolvedConventionErrorCode(status.getStatus(), code, message, context);
    return ResponseEntity.status(status.getStatus()).body(Results.error(errorCode));
  }
}
