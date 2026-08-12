package fun.fengwk.kkstudio.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.studio.canvas.CanvasCommand;
import fun.fengwk.kkstudio.studio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunction;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionResourceRef;
import fun.fengwk.kkstudio.studio.canvas.CanvasGroup;
import fun.fengwk.kkstudio.studio.canvas.CanvasLink;
import fun.fengwk.kkstudio.studio.canvas.CanvasResource;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceNode;
import fun.fengwk.kkstudio.studio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.studio.canvas.CanvasTransform;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Canvas v1 纯领域不变量。 */
class StudioDomainSmokeTest {

  private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");
  private static final CanvasTransform TRANSFORM = new CanvasTransform(1, 2, 100, 80);

  private static UUID id(long n) {
    return new UUID(0L, n);
  }

  private static CanvasResource blob(UUID resourceId, UUID canvasId, String name) {
    return new CanvasResource(resourceId, canvasId, null, null, id(99), name, null, NOW);
  }

  private static CanvasResource text(
      UUID resourceId, UUID canvasId, UUID ownerNodeId, int index, String content) {
    return new CanvasResource(resourceId, canvasId, ownerNodeId, index, null, "t", content, NOW);
  }

  @Test
  void documentAndTransformRejectInvalidState() {
    assertThrows(
        NullPointerException.class, () -> new CanvasDocument(null, "x", 0, null, NOW, NOW));
    assertThrows(
        IllegalArgumentException.class, () -> new CanvasDocument(id(1), " ", 0, null, NOW, NOW));
    assertThrows(
        IllegalArgumentException.class, () -> new CanvasDocument(id(1), "x", -1, null, NOW, NOW));
    assertThrows(IllegalArgumentException.class, () -> new CanvasTransform(Double.NaN, 0, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new CanvasTransform(0, 0, 0, 1));
  }

  @Test
  void documentMayBindOptionalRootThread() {
    assertEquals(id(7), new CanvasDocument(id(1), "c", 0, id(7), NOW, NOW).threadId());
    assertEquals(null, new CanvasDocument(id(1), "c", 0, null, NOW, NOW).threadId());
  }

  @Test
  void resourceEnforcesExactlyOneContentAndOwnerIndexPair() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new CanvasResource(id(1), id(10), null, null, null, "a", null, NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CanvasResource(id(1), id(10), null, null, id(99), "a", "x", NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CanvasResource(id(1), id(10), id(20), null, null, "a", "x", NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CanvasResource(id(1), id(10), null, 0, null, "a", "x", NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CanvasResource(id(1), id(10), id(20), -1, null, "a", "x", NOW));
    assertEquals(id(99), blob(id(1), id(10), "a").blobId());
    assertEquals("x", text(id(1), id(10), id(20), 0, "x").textContent());
  }

  @Test
  void nodeResourcesMustBeOwnedByTheNodeOfSameCanvasAndSameContentKind() {
    CanvasResource text = text(id(1), id(10), id(20), 0, "x");
    CanvasResource blob = blob(id(2), id(10), "b");
    CanvasResource foreign = text(id(3), id(11), id(20), 0, "x");
    CanvasResource unowned = blob(id(4), id(10), "u");
    CanvasResource otherOwner = blob(id(5), id(10), "o");

    assertThrows(
        IllegalArgumentException.class,
        () -> new CanvasResourceNode(id(20), id(10), "n", TRANSFORM, null, List.of(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasResourceNode(
                id(20), id(10), "n", TRANSFORM, null, List.of(text, blob), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasResourceNode(
                id(20), id(10), "n", TRANSFORM, null, List.of(foreign), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasResourceNode(
                id(20), id(10), "n", TRANSFORM, null, List.of(unowned), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasResourceNode(
                id(20), id(10), "n", TRANSFORM, null, List.of(otherOwner), null, null));
  }

  @Test
  void functionNodeMayStartWithoutResources() {
    CanvasResourceNode node =
        new CanvasResourceNode(
            id(20), id(10), "fn", TRANSFORM, null, List.of(), new CanvasFunction("m", "{}"), null);
    assertEquals("m", node.function().modelKey());
  }

  @Test
  void snapshotAndCommandsDefensivelyCopyLists() {
    List<UUID> uploadIds = new ArrayList<>(List.of(id(1)));
    CanvasCommand.CreateResourceNode command =
        new CanvasCommand.CreateResourceNode(id(20), "n", uploadIds, TRANSFORM);
    uploadIds.add(id(2));
    assertEquals(List.of(id(1)), command.uploadIds());

    CanvasDocument document = new CanvasDocument(id(1), "c", 0, null, NOW, NOW);
    CanvasSnapshot snapshot = new CanvasSnapshot(document, List.of(), List.of(), List.of());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.groups().add(null));
  }

  @Test
  void groupAndLinkCarryCanvasIdentityWithoutIndependentLinkId() {
    CanvasGroup group = new CanvasGroup(id(2), id(1), "g", TRANSFORM);
    CanvasLink link = new CanvasLink(id(1), id(3), id(4));
    assertEquals(id(1), group.canvasId());
    assertEquals(id(1), link.canvasId());
    assertThrows(IllegalArgumentException.class, () -> new CanvasLink(id(1), id(3), id(3)));
  }

  @Test
  void functionResourceRefRequiresCanvasRunIdentityResourceAndRole() {
    CanvasFunctionResourceRef input =
        new CanvasFunctionResourceRef(
            id(10), id(20), id(30), id(1), CanvasFunctionResourceRef.Role.INPUT);
    assertEquals(id(10), input.canvasId());
    assertEquals(CanvasFunctionResourceRef.Role.INPUT, input.role());
    assertThrows(
        NullPointerException.class,
        () ->
            new CanvasFunctionResourceRef(
                null, id(20), id(30), id(1), CanvasFunctionResourceRef.Role.INPUT));
    assertThrows(
        NullPointerException.class,
        () -> new CanvasFunctionResourceRef(id(10), id(20), id(30), id(1), null));
  }
}
