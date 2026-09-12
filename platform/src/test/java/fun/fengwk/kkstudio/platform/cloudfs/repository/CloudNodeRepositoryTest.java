package fun.fengwk.kkstudio.platform.cloudfs.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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

  @Test
  void testCrudAndCasVersioning() {
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
}
