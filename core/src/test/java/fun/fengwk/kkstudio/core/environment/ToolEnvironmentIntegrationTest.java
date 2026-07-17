package fun.fengwk.kkstudio.core.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.environment.repo.ToolEnvironmentRepository;
import fun.fengwk.kkstudio.core.environment.service.model.ToolEnvironment;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** H2-backed repository integration coverage for the global Environment registry. */
@SpringBootTest(classes = CoreTestApplication.class)
class ToolEnvironmentIntegrationTest {

  @Autowired private ToolEnvironmentRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void crudCapabilitiesAndHeartbeatReferenceCount() {
    long id = nextEnvironmentId();
    ToolEnvironment environment = newEnvironmentRow(id, "env-" + id);
    assertTrue(repository.create(environment));

    long invocationId = 900_000_000_000_000_000L + id;
    boolean environmentDeleted = false;
    try {
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

      // Insert a fake invocation row referencing this environment and verify the count.
      insertFakeInvocation(invocationId, id);
      assertEquals(1L, repository.countToolInvocations(id));

      // The repository layer itself does not enforce durable-reference checks — that lives
      // on the service layer (covered by ToolEnvironmentServiceImplTest). At the repository
      // level we still observe the Environment row while the invocation exists.

      // Update succeeds while referenced.
      ToolEnvironment toUpdate = repository.getById(id);
      toUpdate.setDescription("updated");
      toUpdate.setName("env-" + id + "-renamed");
      assertTrue(repository.updateById(toUpdate));

      // Clean up the fake invocation first; only then may deletion succeed.
      jdbcTemplate.update("delete from tool_invocation where id = ?", invocationId);
      assertEquals(0L, repository.countToolInvocations(id));
      assertTrue(repository.deleteById(id));
      environmentDeleted = true;
      assertNull(repository.getById(id));
    } finally {
      // Defensive cleanup so a failed assertion does not pollute later tests.
      if (!environmentDeleted) {
        jdbcTemplate.update("delete from tool_invocation where id = ?", invocationId);
        repository.deleteById(id);
      }
    }
  }

  @Test
  void nameUniquenessRejectsDuplicateName() {
    String name = "env-unique-" + System.nanoTime();
    ToolEnvironment a = newEnvironmentRow(nextEnvironmentId(), name);
    ToolEnvironment b = newEnvironmentRow(nextEnvironmentId(), name + "-other");
    assertTrue(repository.create(a));
    assertTrue(repository.create(b));
    try {
      ToolEnvironment conflict = newEnvironmentRow(nextEnvironmentId(), name);
      // name uniqueness is enforced by the DB unique index; the insert must raise.
      assertThrows(DuplicateKeyException.class, () -> repository.create(conflict));
    } finally {
      repository.deleteById(a.getId());
      repository.deleteById(b.getId());
    }
  }

  private ToolEnvironment newEnvironmentRow(long id, String name) {
    ToolEnvironment row = new ToolEnvironment();
    row.setId(id);
    row.setName(name);
    row.setDescription("desc");
    row.setCapabilitiesJson("{\"tools\":[]}");
    return row;
  }

  private void insertFakeInvocation(long invocationId, long environmentId) {
    jdbcTemplate.update(
        "insert into tool_invocation (id, run_id, assistant_entry_id, ordinal, tool_call_id, "
            + "tool_name, tool_version, target_type, environment_id, arguments_json, status, "
            + "permission_action, side_effect, deadline_at) "
            + "values (?, 1, 1, 1, ?, ?, ?, 'ENVIRONMENT', ?, '{}', 'QUEUED', 'ALLOW',"
            + " 'READ_ONLY', current_timestamp(3))",
        invocationId,
        "call-" + environmentId,
        "shell",
        "1",
        environmentId);
  }

  private long nextEnvironmentId() {
    return Math.abs(System.nanoTime());
  }
}
