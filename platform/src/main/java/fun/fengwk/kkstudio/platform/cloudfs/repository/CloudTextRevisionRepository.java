package fun.fengwk.kkstudio.platform.cloudfs.repository;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudTextRevision;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@link CloudTextRevision} 仓储接口。 */
public interface CloudTextRevisionRepository {

  /** 查询指定节点的当前活跃版本。 */
  Optional<CloudTextRevision> findCurrentByNodeId(UUID nodeId);

  /** 查询指定节点的指定版本。 */
  Optional<CloudTextRevision> findByNodeIdAndRevision(UUID nodeId, long revision);

  /** 查询指定节点的所有历史版本列表（按 revision 升序）。 */
  List<CloudTextRevision> listByNodeId(UUID nodeId);

  /** 插入新文本版本。 */
  void insert(CloudTextRevision revision);

  /**
   * CAS 将旧当前版本标记为非当前（{@code is_current = false}）。
   *
   * @param nodeId 节点 ID
   * @param expectedRevision 预期的旧版本号
   * @return 实际更新行数（1 表示成功，0 表示旧版本非当前或版本不符）
   */
  int unsetCurrent(UUID nodeId, long expectedRevision);

  /** 删除指定节点下的所有文本版本。 */
  int deleteByNodeId(UUID nodeId);
}
