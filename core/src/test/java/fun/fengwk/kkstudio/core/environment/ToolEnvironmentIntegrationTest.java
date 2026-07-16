package fun.fengwk.kkstudio.core.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.environment.repo.ToolEnvironmentRepository;
import fun.fengwk.kkstudio.core.environment.service.model.ToolEnvironment;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** H2-backed repository integration coverage for the global Environment registry. */
@SpringBootTest(classes = CoreTestApplication.class)
class ToolEnvironmentIntegrationTest {

  @Autowired private ToolEnvironmentRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void crudCapabilitiesAndHeartbeatReferenceCount() {
    ToolEnvironment environment = new ToolEnvironment();
    long id = nextEnvironmentId();
    environment.setId(id);
    environment.setName("env-" + id);
    environment.setDescription("desc");
    environment.setCapabilitiesJson("{\"tools\":[]}");
    assertTrue(repository.create(environment));

    ToolEnvironment loaded = repository.getById(id);
    assertNotNull(loaded);
    assertEquals(environment.getName(), loaded.getName());
    assertEquals("{\"tools\":[]}", loaded.getCapabilitiesJson());

    assertEquals(loaded, repository.getByName(environment.getName()));

    Page<ToolEnvironment> page = repository.page(new PageQuery(1, 50));
    assertTrue(page.getTotalCount() >= 1);
    assertTrue(page.getResults().stream().anyMatch(row -> row.getId() == id));

    LocalDateTime capabilitySeen = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
    assertTrue(repository.updateCapabilities(id, "{\"tools\":[]}", capabilitySeen));
    ToolEnvironment afterCapability = repository.getById(id);
    assertNotNull(afterCapability.getLastSeenAt());

    LocalDateTime heartbeatSeen = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
    assertTrue(repository.heartbeat(id, heartbeatSeen));

    assertEquals(0L, repository.countToolInvocations(id));
    // Insert a fake invocation row referencing this environment to verify the count.
    long invocationId = 900000000000000000L + id;
    jdbcTemplate.update(
        "insert into tool_invocation (id, run_id, assistant_entry_id, ordinal, tool_call_id, "
            + "tool_name, tool_version, target_type, environment_id, arguments_json, status, "
            + "permission_action, deadline_at) "
            + "values (?, 1, 1, 1, ?, ?, ?, 'ENVIRONMENT', ?, '{}', 'QUEUED', 'ALLOW', "
            + "current_timestamp(3))",
        invocationId,
        "call-" + id,
        "shell",
        "1",
        id);
    assertEquals(1L, repository.countToolInvocations(id));

    loaded.setDescription("updated");
    loaded.setName("env-" + id + "-renamed");
    assertTrue(repository.updateById(loaded));

    assertTrue(repository.deleteById(id));
    assertNull(repository.getById(id));
    // Cleanup the dangling fake invocation so it does not pollute later tests.
    jdbcTemplate.update("delete from tool_invocation where id = ?", invocationId);
  }

  @Test
  void nameUniquenessRejectsDuplicateName() {
    String name = "env-unique-" + System.nanoTime();
    ToolEnvironment a = newEnvironment(name);
    ToolEnvironment b = newEnvironment(name + "-other");
    assertTrue(repository.create(a));
    assertTrue(repository.create(b));
    try {
      ToolEnvironment conflict = newEnvironment(name);
      // name uniqueness is enforced by the DB unique index; the insert must raise.
      assertThrows(DuplicateKeyException.class, () -> repository.create(conflict));
    } finally {
      repository.deleteById(a.getId());
      repository.deleteById(b.getId());
    }
  }

  private ToolEnvironment newEnvironment(String name) {
    ToolEnvironment row = new ToolEnvironment();
    row.setId(nextEnvironmentId());
    row.setName(name);
    row.setCapabilitiesJson("{\"tools\":[]}");
    return row;
  }

  private long nextEnvironmentId() {
    return Math.abs(System.nanoTime());
  }
}
