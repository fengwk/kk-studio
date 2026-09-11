package fun.fengwk.kkstudio.platform.environment.directory;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentDirectoryListResult;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 本节点 Environment 目录查询窄端口：跨节点协调器与产品查询服务都只通过该端口执行本地查询。
 *
 * <p>端口由持有 Environment 会话核心的适配器实现；协调器以构造注入依赖该端口，因此不存在运行期可变注册，也不存在 gateway ↔ coordinator 的双向引用。
 */
public interface LocalDirectoryQueryPort {

  /** 在本节点 READY 连接上执行一次目录查询；不可用时以失败结果完成。 */
  CompletableFuture<EnvironmentDirectoryListResult> executeLocalDirectoryList(
      EnvironmentId environmentId, String path, Duration timeout);

  /** 本节点当前 READY 的 Environment 快照。 */
  Set<EnvironmentId> localReadyEnvironments();
}
