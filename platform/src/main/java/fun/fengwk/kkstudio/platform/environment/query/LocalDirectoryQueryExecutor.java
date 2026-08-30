package fun.fengwk.kkstudio.platform.environment.query;

import fun.fengwk.kkstudio.harness.environment.EnvironmentName;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentDirectoryListResult;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** 本地执行环境只读目录查询的回调接口。 */
@FunctionalInterface
public interface LocalDirectoryQueryExecutor {

  /** 在本地活跃连接上执行目录查询。 */
  CompletableFuture<EnvironmentDirectoryListResult> executeLocalDirectoryList(
      EnvironmentName environmentName, String path, Duration timeout);
}
