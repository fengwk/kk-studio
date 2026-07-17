package fun.fengwk.kkstudio.core.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.studio.StudioWorkspaces;
import fun.fengwk.kkstudio.studio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.studio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.studio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.studio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.studio.runtime.SystemFunctionIds;

/** Minimal durable canvas create/list/command path. */
@SpringBootTest(classes = CoreTestApplication.class)
public class DurableCanvasServiceTest {

  @Autowired private CanvasCommandService canvasCommandService;
  @Autowired private CanvasQueryService canvasQueryService;

  @Test
  public void shouldCreateListAndApplyMinimalCommands() {
    CanvasDocument created = canvasCommandService.createCanvas(StudioWorkspaces.DEFAULT_ID, "研究画布");
    assertEquals(0L, created.revision());
    assertTrue(
        canvasQueryService.listDocuments(StudioWorkspaces.DEFAULT_ID).stream()
            .anyMatch(doc -> doc.id() == created.id()));

    String commands =
        """
        [
          {"type":"create_text_node","name":"笔记","text":"hello","x":10,"y":20},
          {"type":"create_generate_text_node","name":"生成","prompt":"写摘要","x":300,"y":20}
        ]
        """;
    CanvasSnapshot snapshot =
        canvasCommandService.applyCommands(
            created.id(), 0L, "cmd-" + created.id(), "hash-1", commands);
    assertEquals(1L, snapshot.document().revision());
    assertEquals(2, snapshot.nodes().size());
    assertTrue(
        snapshot.nodes().stream()
            .anyMatch(node -> SystemFunctionIds.GENERATE_TEXT.equals(node.nodeType())));

    // idempotent replay
    CanvasSnapshot replay =
        canvasCommandService.applyCommands(
            created.id(), 0L, "cmd-" + created.id(), "hash-1", commands);
    assertEquals(1L, replay.document().revision());
    assertEquals(2, replay.nodes().size());

    assertThrows(
        IllegalStateException.class,
        () ->
            canvasCommandService.applyCommands(
                created.id(), 0L, "cmd-" + created.id(), "hash-other", commands));
    assertThrows(
        IllegalStateException.class,
        () -> canvasCommandService.applyCommands(created.id(), 0L, "cmd-new", "hash-2", commands));
  }
}
