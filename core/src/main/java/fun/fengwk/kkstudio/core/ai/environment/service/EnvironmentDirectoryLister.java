package fun.fengwk.kkstudio.core.ai.environment.service;

import fun.fengwk.kkstudio.harness.tool.EnvironmentName;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Core 端口：按 canonical {@link EnvironmentName} 请求 READY 的 live Environment 浏览一层目录（control-plane，不走
 * Tool Invocation/Permission，不占 active tool slot，可与 active invocation 并行）。
 */
public interface EnvironmentDirectoryLister {

  /**
   * 请求浏览 {@code environmentName} 的 Environment Root 下单层目录。
   *
   * <p>{@code path} 是 canonical 相对 wire 路径（{@code '.'} 表示 root）；非法路径立即以 {@link
   * EnvironmentDirectoryListResult.Failed} 完成，不发送 wire。daemon 失败、environment 离线、断开连接或超时时同样以 {@link
   * EnvironmentDirectoryListResult.Failed} 完成。绝不在远程往返期间阻塞调用方线程。
   */
  CompletableFuture<EnvironmentDirectoryListResult> listDirectory(
      EnvironmentName environmentName, String path, Duration timeout);
}
