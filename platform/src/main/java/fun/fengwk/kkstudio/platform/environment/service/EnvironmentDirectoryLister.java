package fun.fengwk.kkstudio.platform.environment.service;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Platform 端口：按 {@link EnvironmentId} 请求 READY 的 Environment 浏览一层目录。 */
public interface EnvironmentDirectoryLister {

  /**
   * 请求浏览 {@code environmentId} 的 Environment Root 下单层目录。
   *
   * <p>{@code path} 是 canonical 相对 wire 路径（{@code '.'} 表示 root）；非法路径立即以 {@link
   * EnvironmentDirectoryListResult.Failed} 完成，不发送 wire。daemon 失败、环境未知/未 READY、断开连接或超时时同样以 {@link
   * EnvironmentDirectoryListResult.Failed} 完成。绝不在远程往返期间阻塞调用方线程。
   */
  CompletableFuture<EnvironmentDirectoryListResult> listDirectory(
      EnvironmentId environmentId, String path, Duration timeout);
}
