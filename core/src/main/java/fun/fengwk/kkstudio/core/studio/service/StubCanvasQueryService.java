package fun.fengwk.kkstudio.core.studio.service;

import fun.fengwk.kkstudio.studio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.studio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.studio.canvas.CanvasSnapshot;

import java.util.List;
import java.util.Optional;

/** TODO: implement MySQL/H2 canvas query adapters. */
public class StubCanvasQueryService implements CanvasQueryService {

  @Override
  public Optional<CanvasSnapshot> findSnapshot(long canvasId) {
    return Optional.empty();
  }

  @Override
  public List<CanvasDocument> listDocuments(long workspaceId) {
    return List.of();
  }
}
