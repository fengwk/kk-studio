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

/** Workspace CRUD verifies the aggregate's name and JSON invariants. */
@SpringBootTest(classes = CoreTestApplication.class)
public class WorkspaceServiceTest {

  @Autowired private WorkspaceService workspaceService;

  @Test
  public void shouldCreateUpdateAndDeleteWorkspace() {
    String name = "workspace-" + System.nanoTime();
    WorkspaceCreateDTO create = new WorkspaceCreateDTO();
    create.setName(name);
    create.setSettingsJson("{\"defaultYolo\":false}");
    WorkspaceDTO workspace = workspaceService.createWorkspace(create);
    assertEquals(name, workspace.getName());
    assertEquals("{\"defaultYolo\":false}", workspace.getSettingsJson());
    assertThrows(IllegalArgumentException.class, () -> workspaceService.createWorkspace(create));

    WorkspaceUpdateDTO update = new WorkspaceUpdateDTO();
    update.setSettingsJson("{\"defaultYolo\":true}");
    WorkspaceDTO updated = workspaceService.updateWorkspace(Long.parseLong(workspace.getId()), update);
    assertEquals("{\"defaultYolo\":true}", updated.getSettingsJson());
    workspaceService.deleteWorkspace(Long.parseLong(workspace.getId()));
  }
}
