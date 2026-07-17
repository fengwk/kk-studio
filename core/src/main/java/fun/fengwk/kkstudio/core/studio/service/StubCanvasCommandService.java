package fun.fengwk.kkstudio.core.studio.service;

import fun.fengwk.kkstudio.studio.StudioFeatureNotReadyException;
import fun.fengwk.kkstudio.studio.StudioWorkspaces;
import fun.fengwk.kkstudio.studio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.studio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.studio.canvas.CanvasSnapshot;

/** TODO: implement revision CAS, command idempotency and node/link/reference persistence. */
public class StubCanvasCommandService implements CanvasCommandService {

  @Override
  public CanvasDocument createCanvas(long workspaceId, String title) {
    StudioWorkspaces.requireDefault(workspaceId);
    throw new StudioFeatureNotReadyException("CanvasCommandService.createCanvas");
  }

  @Override
  public CanvasDocument renameCanvas(long canvasId, long baseRevision, String title) {
    throw new StudioFeatureNotReadyException("CanvasCommandService.renameCanvas");
  }

  @Override
  public CanvasSnapshot applyCommands(
      long canvasId, long baseRevision, String commandId, String requestHash, String commandsJson) {
    throw new StudioFeatureNotReadyException("CanvasCommandService.applyCommands");
  }
}
