package fun.fengwk.kkstudio.studio.canvas;

import java.util.Objects;

public record CanvasDocument(
    long id,
    long workspaceId,
    String title,
    int schemaVersion,
    long revision,
    CanvasLifecycle lifecycle,
    String homeViewportJson) {

  public CanvasDocument {
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(lifecycle, "lifecycle");
    Objects.requireNonNull(homeViewportJson, "homeViewportJson");
  }
}
