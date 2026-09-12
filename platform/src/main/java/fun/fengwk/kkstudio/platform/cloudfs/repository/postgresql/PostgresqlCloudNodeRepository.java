package fun.fengwk.kkstudio.platform.cloudfs.repository.postgresql;

import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNode;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNodeKind;
import fun.fengwk.kkstudio.platform.cloudfs.repository.CloudNodeRepository;
import fun.fengwk.kkstudio.platform.cloudfs.repository.postgresql.mapper.CloudNodeMapper;
import fun.fengwk.kkstudio.platform.cloudfs.repository.postgresql.model.CloudNodeDO;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 基于 PostgreSQL MyBatis 的 {@link CloudNodeRepository} 实现。 */
@Repository
public class PostgresqlCloudNodeRepository implements CloudNodeRepository {

  private final CloudNodeMapper mapper;

  public PostgresqlCloudNodeRepository(CloudNodeMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  public Optional<CloudNode> findById(UUID id) {
    if (id == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(toDomain(mapper.getById(id)));
  }

  @Override
  public Optional<CloudNode> lockById(UUID id) {
    if (id == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(toDomain(mapper.lockById(id)));
  }

  @Override
  public Optional<CloudNode> findByParentIdAndName(UUID parentId, String name) {
    if (name == null || name.isEmpty()) {
      return Optional.empty();
    }
    CloudNodeDO nodeDO =
        parentId == null
            ? mapper.getRootChildByName(name)
            : mapper.getByParentIdAndName(parentId, name);
    return Optional.ofNullable(toDomain(nodeDO));
  }

  @Override
  public List<CloudNode> findByParentId(UUID parentId) {
    List<CloudNodeDO> list =
        parentId == null ? mapper.listRootChildren() : mapper.listByParentId(parentId);
    return list.stream().map(this::toDomain).filter(Objects::nonNull).toList();
  }

  @Override
  public int countByParentId(UUID parentId) {
    return parentId == null ? mapper.countRootChildren() : mapper.countByParentId(parentId);
  }

  @Override
  public void insert(CloudNode node) {
    Objects.requireNonNull(node, "node");
    CloudNodeDO nodeDO = toDO(node);
    mapper.insert(nodeDO);
  }

  @Override
  public boolean insertIfAbsent(CloudNode node) {
    Objects.requireNonNull(node, "node");
    CloudNodeDO nodeDO = toDO(node);
    return mapper.insertIfAbsent(nodeDO) > 0;
  }

  @Override
  public int updateParentAndName(
      UUID id, UUID newParentId, String newName, long expectedVersion, Instant updatedAt) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(newName, "newName");
    Instant updateTime = updatedAt != null ? updatedAt : Instant.now();
    return mapper.updateParentAndName(id, newParentId, newName, expectedVersion, updateTime);
  }

  @Override
  public int touch(UUID id, Instant updatedAt) {
    Objects.requireNonNull(id, "id");
    Instant updateTime = updatedAt != null ? updatedAt : Instant.now();
    return mapper.touch(id, updateTime);
  }

  @Override
  public int deleteByIdAndVersion(UUID id, long expectedVersion) {
    if (id == null) {
      return 0;
    }
    return mapper.deleteByIdAndVersion(id, expectedVersion);
  }

  @Override
  public int deleteById(UUID id) {
    if (id == null) {
      return 0;
    }
    return mapper.deleteById(id);
  }

  private CloudNode toDomain(CloudNodeDO nodeDO) {
    if (nodeDO == null) {
      return null;
    }
    return CloudNode.builder()
        .id(nodeDO.getId())
        .parentId(nodeDO.getParentId())
        .name(nodeDO.getName())
        .kind(nodeDO.getKind() != null ? CloudNodeKind.valueOf(nodeDO.getKind()) : null)
        .version(nodeDO.getVersion() != null ? nodeDO.getVersion() : 0L)
        .blobId(nodeDO.getBlobId())
        .createdAt(nodeDO.getCreateTime())
        .updatedAt(nodeDO.getUpdateTime())
        .build();
  }

  private CloudNodeDO toDO(CloudNode node) {
    if (node == null) {
      return null;
    }
    return CloudNodeDO.builder()
        .id(node.getId())
        .parentId(node.getParentId())
        .name(node.getName())
        .kind(node.getKind() != null ? node.getKind().name() : null)
        .version(node.getVersion())
        .blobId(node.getBlobId())
        .createTime(node.getCreatedAt())
        .updateTime(node.getUpdatedAt())
        .build();
  }
}
