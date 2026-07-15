package fun.fengwk.kkstudio.core.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.harness.runtime.session.SessionTree;
import fun.fengwk.kkstudio.share.model.WorkspaceCreateDTO;
import fun.fengwk.kkstudio.share.model.WorkspaceDTO;
import fun.fengwk.kkstudio.share.model.WorkspaceUpdateDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Workspace CRUD verifies lookup and JSON partial-update invariants. */
@SpringBootTest(classes = CoreTestApplication.class)
public class WorkspaceServiceTest {

  @Autowired private WorkspaceService workspaceService;
  @Autowired private SessionTree sessionTree;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  public void shouldCreateGetPartiallyUpdateAndDeleteWorkspace() {
    String name = "workspace-" + System.nanoTime();
    WorkspaceCreateDTO create = new WorkspaceCreateDTO();
    create.setName(name);
    create.setSettingsJson("{\"extensions\":{\"theme\":\"dark\"},\"defaultYolo\":false}");
    WorkspaceDTO workspace = workspaceService.createWorkspace(create);
    long workspaceId = Long.parseLong(workspace.getId());
    assertEquals(name, workspaceService.getWorkspace(workspaceId).getName());
    assertThrows(IllegalArgumentException.class, () -> workspaceService.createWorkspace(create));

    // A name-only patch must preserve the existing JSON settings.
    WorkspaceUpdateDTO nameOnlyUpdate = new WorkspaceUpdateDTO();
    nameOnlyUpdate.setName(name + "-renamed");
    WorkspaceDTO renamed = workspaceService.updateWorkspace(workspaceId, nameOnlyUpdate);
    assertEquals(
        "{\"extensions\":{\"theme\":\"dark\"},\"defaultYolo\":false,\"permission\":{}}",
        renamed.getSettingsJson());

    WorkspaceUpdateDTO settingsUpdate = new WorkspaceUpdateDTO();
    settingsUpdate.setSettingsJson("{\"defaultYolo\":true}");
    WorkspaceDTO updated = workspaceService.updateWorkspace(workspaceId, settingsUpdate);
    assertEquals("{\"defaultYolo\":true,\"permission\":{}}", updated.getSettingsJson());
    workspaceService.deleteWorkspace(workspaceId);
  }

  /** Harness Session 已全局化，不再阻止 Workspace 删除。 */
  @Test
  public void shouldDeleteWorkspaceEvenWhenGlobalHarnessSessionExists() {
    WorkspaceCreateDTO create = new WorkspaceCreateDTO();
    create.setName("session-workspace-" + System.nanoTime());
    WorkspaceDTO workspace = workspaceService.createWorkspace(create);
    long workspaceId = Long.parseLong(workspace.getId());
    sessionTree.create(null, "session");

    workspaceService.deleteWorkspace(workspaceId);
    jdbcTemplate.update("delete from harness_session");
  }

  @Test
  public void shouldRejectInvalidWorkspaceSettingsJson() {
    WorkspaceCreateDTO invalidCreate = new WorkspaceCreateDTO();
    invalidCreate.setName("invalid-workspace-" + System.nanoTime());
    invalidCreate.setSettingsJson("[]");
    assertThrows(
        IllegalArgumentException.class, () -> workspaceService.createWorkspace(invalidCreate));

    WorkspaceCreateDTO validCreate = new WorkspaceCreateDTO();
    validCreate.setName("valid-workspace-" + System.nanoTime());
    WorkspaceDTO workspace = workspaceService.createWorkspace(validCreate);
    WorkspaceUpdateDTO invalidUpdate = new WorkspaceUpdateDTO();
    invalidUpdate.setSettingsJson("not-json");
    long workspaceId = Long.parseLong(workspace.getId());
    assertThrows(
        IllegalArgumentException.class,
        () -> workspaceService.updateWorkspace(workspaceId, invalidUpdate));
    assertEquals(
        "{\"permission\":{},\"defaultYolo\":false}",
        workspaceService.getWorkspace(workspaceId).getSettingsJson());
    workspaceService.deleteWorkspace(workspaceId);
  }
}
