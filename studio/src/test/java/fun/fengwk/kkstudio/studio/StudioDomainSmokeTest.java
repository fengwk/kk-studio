package fun.fengwk.kkstudio.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.studio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.studio.canvas.CanvasLink;
import fun.fengwk.kkstudio.studio.canvas.CanvasNode;
import fun.fengwk.kkstudio.studio.canvas.CanvasNodeKind;
import fun.fengwk.kkstudio.studio.canvas.NodeTransform;

/** 纯领域值类型及其不变量的冒烟覆盖。 */
class StudioDomainSmokeTest {

  @Test
  void canvasDocumentCarriesRevision() {
    CanvasDocument document = new CanvasDocument(1L, "demo", 0L, "{}");
    assertEquals("demo", document.title());
    assertEquals(0L, document.revision());
  }

  @Test
  void canvasDocumentRejectsNonPositiveId() {
    assertThrows(IllegalArgumentException.class, () -> new CanvasDocument(0L, "demo", 0L, "{}"));
    assertThrows(IllegalArgumentException.class, () -> new CanvasDocument(-1L, "demo", 0L, "{}"));
  }

  @Test
  void canvasDocumentRejectsBlankTitle() {
    assertThrows(IllegalArgumentException.class, () -> new CanvasDocument(1L, " ", 0L, "{}"));
    assertThrows(IllegalArgumentException.class, () -> new CanvasDocument(1L, null, 0L, "{}"));
  }

  @Test
  void canvasDocumentRejectsNegativeRevision() {
    assertThrows(IllegalArgumentException.class, () -> new CanvasDocument(1L, "demo", -1L, "{}"));
  }

  @Test
  void canvasDocumentRejectsBlankViewport() {
    assertThrows(IllegalArgumentException.class, () -> new CanvasDocument(1L, "demo", 0L, ""));
    assertThrows(IllegalArgumentException.class, () -> new CanvasDocument(1L, "demo", 0L, null));
  }

  @Test
  void nodeTransformKeepsGeometry() {
    NodeTransform transform = new NodeTransform(10d, 20d, 320d, 240d);
    assertEquals(10d, transform.x());
    assertEquals(240d, transform.height());
  }

  @Test
  void nodeTransformRejectsNonFiniteOrNonPositive() {
    assertThrows(IllegalArgumentException.class, () -> new NodeTransform(0d, 0d, 0d, 1d));
    assertThrows(IllegalArgumentException.class, () -> new NodeTransform(0d, 0d, 1d, 0d));
    assertThrows(IllegalArgumentException.class, () -> new NodeTransform(Double.NaN, 0d, 1d, 1d));
    assertThrows(
        IllegalArgumentException.class,
        () -> new NodeTransform(0d, 0d, Double.POSITIVE_INFINITY, 1d));
  }

  @Test
  void canvasLinkRejectsSelfLoopAndZero() {
    assertThrows(IllegalArgumentException.class, () -> new CanvasLink(1L, 7L, 7L));
    assertThrows(IllegalArgumentException.class, () -> new CanvasLink(0L, 1L, 2L));
    assertThrows(IllegalArgumentException.class, () -> new CanvasLink(1L, 0L, 2L));
    assertThrows(IllegalArgumentException.class, () -> new CanvasLink(1L, 1L, 0L));
  }

  @Test
  void canvasNodeRejectsBlankFields() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasNode(
                0L,
                CanvasNodeKind.RESOURCE,
                "text",
                "name",
                new NodeTransform(0, 0, 100, 100),
                "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasNode(
                1L, CanvasNodeKind.RESOURCE, "", "name", new NodeTransform(0, 0, 100, 100), "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasNode(
                1L, CanvasNodeKind.RESOURCE, "text", "", new NodeTransform(0, 0, 100, 100), "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasNode(
                1L,
                CanvasNodeKind.RESOURCE,
                "text",
                "name",
                new NodeTransform(0, 0, 100, 100),
                ""));
  }
}
