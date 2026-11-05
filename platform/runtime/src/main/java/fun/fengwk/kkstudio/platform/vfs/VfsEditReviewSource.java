package fun.fengwk.kkstudio.platform.vfs;

import fun.fengwk.kkstudio.harness.api.tool.ToolEditReview;
import fun.fengwk.kkstudio.harness.environment.capability.ExpectedFile;
import fun.fengwk.kkstudio.harness.spi.ToolInvocationRequest;

import java.util.UUID;

/**
 * 需要审批的 {@code vfs:} 编辑的审查事实来源。
 *
 * <p>网关在 preflight 阶段向它索取用户审查用的 diff；审批通过后、执行之前再用同一接口校验文件快照未变化。实现由平台 VFS 提供，读取的是会话 文件存储中的真实内容。
 */
public interface VfsEditReviewSource {

  /**
   * 生成编辑审查；路径不可写、文件不存在、匹配不唯一或 diff 过大时抛出 {@link VfsEditRejectedException}。
   *
   * @param request 冻结的工具调用请求
   * @param rootSessionId 执行根会话 ID
   * @param rootThreadId 执行根线程 ID
   */
  ToolEditReview prepare(ToolInvocationRequest request, UUID rootSessionId, UUID rootThreadId);

  /**
   * 校验审批时的文件快照仍是当前内容；内容已变化时抛出 {@link VfsEditRejectedException}。
   *
   * @param rootSessionId 执行根会话 ID
   * @param rootThreadId 执行根线程 ID
   * @param expectedFile 审批携带的原文件快照
   */
  void verifySnapshot(UUID rootSessionId, UUID rootThreadId, ExpectedFile expectedFile);
}
