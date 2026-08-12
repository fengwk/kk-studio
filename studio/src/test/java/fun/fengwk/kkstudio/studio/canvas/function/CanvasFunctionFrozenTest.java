package fun.fengwk.kkstudio.studio.canvas.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Function 冻结计划携带 UUID 身份与执行所需媒体快照。 */
class CanvasFunctionFrozenTest {

  private static final UUID NODE_ID = new UUID(0L, 20);
  private static final UUID CANVAS_ID = new UUID(0L, 10);
  private static final UUID REQUEST_ID = new UUID(0L, 30);
  private static final UUID RESOURCE_ID = new UUID(0L, 1);
  private static final UUID BLOB_ID = new UUID(0L, 99);

  @Test
  void frozenReferenceCarriesResourceIdBlobIdAndMediaFacts() {
    CanvasFunctionFrozenReference reference =
        new CanvasFunctionFrozenReference(
            NODE_ID,
            0,
            RESOURCE_ID,
            BLOB_ID,
            CanvasResourceKind.IMAGE,
            "a.png",
            "image/png",
            1024,
            "{}");
    assertEquals(RESOURCE_ID, reference.resourceId());
    assertEquals(BLOB_ID, reference.blobId());
    assertEquals(CanvasResourceKind.IMAGE, reference.kind());
    assertEquals(1024L, reference.size());

    assertThrows(
        NullPointerException.class,
        () ->
            new CanvasFunctionFrozenReference(
                null,
                0,
                RESOURCE_ID,
                BLOB_ID,
                CanvasResourceKind.IMAGE,
                "a",
                "image/png",
                1,
                "{}"));
    assertThrows(
        NullPointerException.class,
        () ->
            new CanvasFunctionFrozenReference(
                NODE_ID, 0, null, BLOB_ID, CanvasResourceKind.IMAGE, "a", "image/png", 1, "{}"));
    assertThrows(
        NullPointerException.class,
        () ->
            new CanvasFunctionFrozenReference(
                NODE_ID,
                0,
                RESOURCE_ID,
                null,
                CanvasResourceKind.IMAGE,
                "a",
                "image/png",
                1,
                "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasFunctionFrozenReference(
                NODE_ID,
                -1,
                RESOURCE_ID,
                BLOB_ID,
                CanvasResourceKind.IMAGE,
                "a",
                "image/png",
                1,
                "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasFunctionFrozenReference(
                NODE_ID,
                0,
                RESOURCE_ID,
                BLOB_ID,
                CanvasResourceKind.IMAGE,
                "a",
                "image/png",
                -1,
                "{}"));
  }

  @Test
  void frozenRunCarriesUuidIdentityAndTarget() {
    CanvasFunctionModel model =
        new CanvasFunctionModel(
            "fake-image",
            "Fake Image",
            CanvasResourceKind.IMAGE,
            new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()),
            List.of());
    CanvasFunctionFrozenRun run =
        new CanvasFunctionFrozenRun(
            CANVAS_ID,
            NODE_ID,
            "fn",
            REQUEST_ID,
            model,
            new CanvasFunctionConfig(List.of(new CanvasFunctionConfig.TextSegment("x")), Map.of()),
            List.of(),
            "out",
            new UUID(0L, 100),
            "STARTED",
            Map.of());
    assertEquals(NODE_ID, run.nodeId());
    assertEquals(REQUEST_ID, run.requestId());
    assertThrows(
        NullPointerException.class,
        () ->
            new CanvasFunctionFrozenRun(
                null,
                NODE_ID,
                "fn",
                REQUEST_ID,
                model,
                new CanvasFunctionConfig(
                    List.of(new CanvasFunctionConfig.TextSegment("x")), Map.of()),
                List.of(),
                "out",
                new UUID(0L, 100),
                "STARTED",
                Map.of()));
  }
}
