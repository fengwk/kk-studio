package fun.fengwk.kkstudio.core.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.share.model.WorkspaceCreateDTO;
import fun.fengwk.kkstudio.share.model.WorkspaceDTO;
import fun.fengwk.kkstudio.share.model.WorkspaceUpdateDTO;

/** Workspace CRUD verifies lookup and JSON partial-update invariants. */
@SpringBootTest(classes = CoreTestApplication.class)
public class WorkspaceServiceTest {

  @Autowired private WorkspaceService workspaceService;

  @Test
  public void shouldCreateGetPartiallyUpdateAndDeleteWorkspace() {
    String name = "workspace-" + System.nanoTime();
    WorkspaceCreateDTO create = new WorkspaceCreateDTO();
    create.setName(name);
    create.setSettingsJson("{\"defaultYolo\":false}");
    WorkspaceDTO workspace = workspaceService.createWorkspace(create);
    long workspaceId = Long.parseLong(workspace.getId());
    assertEquals(name, workspaceService.getWorkspace(workspaceId).getName());
    assertThrows(IllegalArgumentException.class, () -> workspaceService.createWorkspace(create));

    // A name-only patch must preserve the existing JSON settings.
    WorkspaceUpdateDTO nameOnlyUpdate = new WorkspaceUpdateDTO();
    nameOnlyUpdate.setName(name + "-renamed");
    WorkspaceDTO renamed = workspaceService.updateWorkspace(workspaceId, nameOnlyUpdate);
    assertEquals("{\"defaultYolo\":false}", renamed.getSettingsJson());

    WorkspaceUpdateDTO settingsUpdate = new WorkspaceUpdateDTO();
    settingsUpdate.setSettingsJson("{\"defaultYolo\":true}");
    WorkspaceDTO updated = workspaceService.updateWorkspace(workspaceId, settingsUpdate);
    assertEquals("{\"defaultYolo\":true}", updated.getSettingsJson());
    workspaceService.deleteWorkspace(workspaceId);
  }

  @Test
  public void shouldRejectInvalidWorkspaceSettingsJson() {
    WorkspaceCreateDTO invalidCreate = new WorkspaceCreateDTO();
    invalidCreate.setName("invalid-workspace-" + System.nanoTime());
    invalidCreate.setSettingsJson("[]");
    assertThrows(IllegalArgumentException.class, () -> workspaceService.createWorkspace(invalidCreate));

    WorkspaceCreateDTO validCreate = new WorkspaceCreateDTO();
    validCreate.setName("valid-workspace-" + System.nanoTime());
    WorkspaceDTO workspace = workspaceService.createWorkspace(validCreate);
    WorkspaceUpdateDTO invalidUpdate = new WorkspaceUpdateDTO();
    invalidUpdate.setSettingsJson("not-json");
    long workspaceId = Long.parseLong(workspace.getId());
    assertThrows(
        IllegalArgumentException.class, () -> workspaceService.updateWorkspace(workspaceId, invalidUpdate));
    assertEquals("{}", workspaceService.getWorkspace(workspaceId).getSettingsJson());
    workspaceService.deleteWorkspace(workspaceId);
  }
}
