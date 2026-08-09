package fun.fengwk.kkstudio.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.studio.canvas.CanvasCommand;
import fun.fengwk.kkstudio.studio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunction;
import fun.fengwk.kkstudio.studio.canvas.CanvasGroup;
import fun.fengwk.kkstudio.studio.canvas.CanvasLink;
import fun.fengwk.kkstudio.studio.canvas.CanvasResource;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceNode;
import fun.fengwk.kkstudio.studio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.studio.canvas.CanvasTransform;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Canvas v1 纯领域不变量。 */
class StudioDomainSmokeTest {

  private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");
  private static final CanvasTransform TRANSFORM = new CanvasTransform(1, 2, 100, 80);

  @Test
  void documentAndTransformRejectInvalidState() {
    assertThrows(IllegalArgumentException.class, () -> new CanvasDocument(0, "x", 0, NOW, NOW));
    assertThrows(IllegalArgumentException.class, () -> new CanvasDocument(1, " ", 0, NOW, NOW));
    assertThrows(IllegalArgumentException.class, () -> new CanvasDocument(1, "x", -1, NOW, NOW));
    assertThrows(IllegalArgumentException.class, () -> new CanvasTransform(Double.NaN, 0, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new CanvasTransform(0, 0, 0, 1));
  }

  @Test
  void resourceTextShapeIsStrict() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasResource(
                1, 2, CanvasResourceKind.TEXT, "text/markdown", "a", 1, null, "{}", NOW));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasResource(
                1, 2, CanvasResourceKind.IMAGE, "image/png", "a", 1, "bad", "{}", NOW));
  }

  @Test
  void ordinaryNodeRequiresSameKindSameCanvasResources() {
    CanvasResource text =
        new CanvasResource(1, 10, CanvasResourceKind.TEXT, "text/markdown", "a", 1, "x", "{}", NOW);
    CanvasResource image =
        new CanvasResource(2, 10, CanvasResourceKind.IMAGE, "image/png", "b", 1, null, "{}", NOW);
    CanvasResource foreign =
        new CanvasResource(3, 11, CanvasResourceKind.TEXT, "text/markdown", "c", 1, "x", "{}", NOW);

    assertThrows(
        IllegalArgumentException.class,
        () -> new CanvasResourceNode(20, 10, "n", TRANSFORM, null, List.of(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasResourceNode(20, 10, "n", TRANSFORM, null, List.of(text, image), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CanvasResourceNode(20, 10, "n", TRANSFORM, null, List.of(foreign), null, null));
  }

  @Test
  void functionNodeMayStartWithoutResources() {
    CanvasResourceNode node =
        new CanvasResourceNode(
            20, 10, "fn", TRANSFORM, null, List.of(), new CanvasFunction("m", "{}"), null);
    assertEquals("m", node.function().modelKey());
  }

  @Test
  void snapshotAndCommandsDefensivelyCopyLists() {
    List<Long> ids = new ArrayList<>(List.of(1L));
    CanvasCommand.CreateResourceNode command =
        new CanvasCommand.CreateResourceNode("n", ids, TRANSFORM);
    ids.add(2L);
    assertEquals(List.of(1L), command.resourceIds());

    CanvasDocument document = new CanvasDocument(1, "c", 0, NOW, NOW);
    CanvasSnapshot snapshot = new CanvasSnapshot(document, List.of(), List.of(), List.of());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.groups().add(null));
  }

  @Test
  void groupAndLinkCarryCanvasIdentityWithoutIndependentLinkId() {
    CanvasGroup group = new CanvasGroup(2, 1, "g", TRANSFORM);
    CanvasLink link = new CanvasLink(1, 3, 4);
    assertEquals(1L, group.canvasId());
    assertEquals(1L, link.canvasId());
    assertThrows(IllegalArgumentException.class, () -> new CanvasLink(1, 3, 3));
  }
}
