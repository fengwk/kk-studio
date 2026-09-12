package fun.fengwk.kkstudio.platform.cloudfs.service;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNode;

import java.util.UUID;

/**
 * System Tool Artifact 服务。
 *
 * <p>专用于平台内部创建和维护 {@code /.artifacts/tool-results/{threadId}/{invocationId}.txt|json} 路径下的 BLOB 节点。
 */
public interface CloudArtifactService {

  /**
   * 为工具大输出创建或幂等重放 Tool Artifact BLOB 节点。
   *
   * <p>规则：
   *
   * <ul>
   *   <li>使用 canonical UUID 与扩展名构造确定性虚拟路径；
   *   <li>同一路径已存在且底层 storage_blob 的 size 与 sha256 均相同时，幂等成功，不产生重复 retain；
   *   <li>同一路径已存在但 size 或 sha256 不一致时，作为不变量违规抛出异常，绝不静默覆盖；
   *   <li>新建 Artifact 节点时，自动创建所属 thread 目录，并对底层 storage_blob 执行一份独立 retain。
   * </ul>
   *
   * @param threadId 会话线程 UUID
   * @param invocationId 工具调用 UUID
   * @param extension 文件扩展名，仅接受精确 txt 或 json
   * @param blobId 完整内容的 storage_blob UUID
   * @return Artifact BLOB 节点
   */
  CloudNode createToolArtifact(UUID threadId, UUID invocationId, String extension, UUID blobId);
}
