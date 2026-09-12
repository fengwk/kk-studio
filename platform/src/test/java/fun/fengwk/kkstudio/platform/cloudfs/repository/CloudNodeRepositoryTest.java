package fun.fengwk.kkstudio.platform.cloudfs.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNode;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNodeKind;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@link CloudNodeRepository} 持久层集成测试。 */
class CloudNodeRepositoryTest extends PostgresSpringTestSupport {

  @Autowired private CloudNodeRepository repository;

  /** 验证基本 CRUD、CAS 版本控制、排他锁查询以及非空断言。 */
  @Test
  void testCrudAndCasVersioningAndLocking() {
    UUID rootChildId = UUID.randomUUID();
    CloudNode rootChild =
        CloudNode.builder()
            .id(rootChildId)
            .parentId(null)
            .name("custom_root")
            .kind(CloudNodeKind.DIRECTORY)
            .version(0L)
            .build();

    // insertIfAbsent returns true on first insertion
    assertTrue(repository.insertIfAbsent(rootChild));
    // insertIfAbsent returns false on duplicate (null parent + name)
    assertFalse(repository.insertIfAbsent(rootChild));

    Optional<CloudNode> found = repository.findById(rootChildId);
    assertTrue(found.isPresent());
    assertEquals("custom_root", found.get().getName());
    assertEquals(0L, found.get().getVersion());

    // findByParentIdAndName with null parent
    Optional<CloudNode> foundByName = repository.findByParentIdAndName(null, "custom_root");
    assertTrue(foundByName.isPresent());
    assertEquals(rootChildId, foundByName.get().getId());

    // findByParentIdAndNameForUpdate for root child
    Optional<CloudNode> lockedRoot = repository.findByParentIdAndNameForUpdate(null, "custom_root");
    assertTrue(lockedRoot.isPresent());
    assertEquals(rootChildId, lockedRoot.get().getId());

    // Insert child node under custom_root
    UUID childId = UUID.randomUUID();
    CloudNode child =
        CloudNode.builder()
            .id(childId)
            .parentId(rootChildId)
            .name("child_dir")
            .kind(CloudNodeKind.DIRECTORY)
            .version(0L)
            .build();
    repository.insert(child);

    assertEquals(1, repository.countByParentId(rootChildId));
    List<CloudNode> children = repository.findByParentId(rootChildId);
    assertEquals(1, children.size());
    assertEquals("child_dir", children.get(0).getName());

    // findByParentIdAndNameForUpdate for non-root child
    Optional<CloudNode> lockedChild =
        repository.findByParentIdAndNameForUpdate(rootChildId, "child_dir");
    assertTrue(lockedChild.isPresent());
    assertEquals(childId, lockedChild.get().getId());

    // CAS move / rename
    int updatedStale =
        repository.updateParentAndName(childId, rootChildId, "renamed_child", 99L, Instant.now());
    assertEquals(0, updatedStale);

    int updatedSuccess =
        repository.updateParentAndName(childId, rootChildId, "renamed_child", 0L, Instant.now());
    assertEquals(1, updatedSuccess);

    CloudNode updated = repository.findById(childId).orElseThrow();
    assertEquals("renamed_child", updated.getName());
    assertEquals(1L, updated.getVersion());

    // Touch node
    Instant newTime = Instant.now().plusSeconds(10);
    int touched = repository.touch(childId, newTime);
    assertEquals(1, touched);

    // CAS delete
    int deletedStale = repository.deleteByIdAndVersion(childId, 0L);
    assertEquals(0, deletedStale);

    int deletedSuccess = repository.deleteByIdAndVersion(childId, 1L);
    assertEquals(1, deletedSuccess);
    assertFalse(repository.findById(childId).isPresent());
  }

  /** 验证数据库层对节点名称 UTF-8 字节长度 <= 255 的约束强制检查。 */
  @Test
  void testSchemaCheckNameByteLengthLimit() {
    // 256 bytes exceeds octet_length(name) <= 255 check constraint
    String longName = "a".repeat(256);
    CloudNode oversizedNode =
        CloudNode.builder()
            .id(UUID.randomUUID())
            .parentId(null)
            .name(longName)
            .kind(CloudNodeKind.DIRECTORY)
            .version(0L)
            .build();

    assertThrows(DataIntegrityViolationException.class, () -> repository.insert(oversizedNode));
  }

  /** 验证仓储接口对入参 null 的严格快速失败校验。 */
  @Test
  void testRepositoryRejectsNullInputs() {
    assertThrows(NullPointerException.class, () -> repository.findById(null));
    assertThrows(NullPointerException.class, () -> repository.findByParentIdAndName(null, null));
    assertThrows(
        NullPointerException.class, () -> repository.findByParentIdAndNameForUpdate(null, null));
    assertThrows(NullPointerException.class, () -> repository.insert(null));
    assertThrows(NullPointerException.class, () -> repository.insertIfAbsent(null));
    assertThrows(
        NullPointerException.class,
        () -> repository.updateParentAndName(null, null, "a", 0L, Instant.now()));
    assertThrows(
        NullPointerException.class,
        () -> repository.updateParentAndName(UUID.randomUUID(), null, null, 0L, Instant.now()));
    assertThrows(
        NullPointerException.class,
        () -> repository.updateParentAndName(UUID.randomUUID(), null, "a", 0L, null));
    assertThrows(NullPointerException.class, () -> repository.touch(null, Instant.now()));
    assertThrows(NullPointerException.class, () -> repository.touch(UUID.randomUUID(), null));
    assertThrows(NullPointerException.class, () -> repository.deleteByIdAndVersion(null, 0L));
  }
}
