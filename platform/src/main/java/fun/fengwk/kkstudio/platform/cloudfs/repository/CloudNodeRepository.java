package fun.fengwk.kkstudio.platform.cloudfs.repository;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@link CloudNode} 仓储接口。 */
public interface CloudNodeRepository {

  /** 根据 ID 查询节点。 */
  Optional<CloudNode> findById(UUID id);

  /**
   * 根据父节点 ID 和名称查询唯一定位节点。
   *
   * @param parentId 父节点 ID；若为 null 则查询根目录直接子节点
   * @param name 分段名称
   */
  Optional<CloudNode> findByParentIdAndName(UUID parentId, String name);

  /**
   * 查询指定父节点下的所有直接子节点，按名称升序排列。
   *
   * @param parentId 父节点 ID；若为 null 则查询根目录直接子节点
   */
  List<CloudNode> findByParentId(UUID parentId);

  /**
   * 统计指定父节点下的直接子节点数量。
   *
   * @param parentId 父节点 ID；若为 null 则统计根目录直接子节点
   */
  int countByParentId(UUID parentId);

  /** 插入新节点。 */
  void insert(CloudNode node);

  /** 尝试插入新节点；若 (parent_id, name) 冲突则忽略（返回 false）。 */
  boolean insertIfAbsent(CloudNode node);

  /**
   * CAS 移动或重命名节点：更新 parent_id、name 与 updated_at，并将 version + 1。
   *
   * @return 更新成功的行数（0 表示版本冲突或不存在）
   */
  int updateParentAndName(
      UUID id, UUID newParentId, String newName, long expectedVersion, Instant updatedAt);

  /** 更新节点 updated_at 时间戳。 */
  int touch(UUID id, Instant updatedAt);

  /**
   * CAS 删除节点。
   *
   * @return 删除成功的行数（0 表示版本冲突或不存在）
   */
  int deleteByIdAndVersion(UUID id, long expectedVersion);
}
