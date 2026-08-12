package fun.fengwk.kkstudio.core.studio.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.studio.canvas.CanvasFunction;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.studio.canvas.CanvasGroup;
import fun.fengwk.kkstudio.studio.canvas.CanvasGroupPatch;
import fun.fengwk.kkstudio.studio.canvas.CanvasLink;
import fun.fengwk.kkstudio.studio.canvas.CanvasLinkPatch;
import fun.fengwk.kkstudio.studio.canvas.CanvasNodePatch;
import fun.fengwk.kkstudio.studio.canvas.CanvasPatch;
import fun.fengwk.kkstudio.studio.canvas.CanvasResource;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceNode;
import fun.fengwk.kkstudio.studio.canvas.CanvasTransform;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Redis Patch codec 必须稳定 round-trip 全部 patch 形态，且损坏记录 fail closed。 */
class CanvasPatchJsonCodecTest {

  private static final UUID CANVAS = uuid(1);
  private static final UUID GROUP = uuid(2);
  private static final UUID TEXT_NODE = uuid(3);
  private static final UUID FUNCTION_NODE = uuid(4);
  private static final UUID REMOVED_NODE = uuid(5);
  private static final UUID TEXT_RESOURCE = uuid(6);
  private static final UUID BLOB_RESOURCE = uuid(7);
  private static final UUID BLOB = uuid(8);
  private static final UUID REQUEST = uuid(9);
  private static final Instant NOW = Instant.parse("2026-08-12T00:00:00.123456789Z");

  private final CanvasPatchJsonCodec codec =
      new CanvasPatchJsonCodec(
          new ObjectMapper()
              .findAndRegisterModules()
              .setSerializationInclusion(JsonInclude.Include.NON_NULL));

  @Test
  void roundTripsAllPatchShapesWithoutDerivedDomainGetters() {
    CanvasPatch patch = patch();

    String json = codec.encode(patch);

    assertEquals(patch, codec.decode(json));
    assertTrue(json.contains("\"baseVersion\":4") || json.contains("\"baseVersion\":\"4\""));
    assertTrue(json.contains("\"op\":\"UPSERT\""));
    assertTrue(json.contains("\"op\":\"REMOVE\""));
    assertTrue(json.contains("\"createdAt\":\"2026-08-12T00:00:00.123456789Z\""));
    assertFalse(json.contains("\"text\":true"));
    assertFalse(json.contains("\"blob\":true"));
    assertFalse(json.contains("\"empty\":"));
  }

  @Test
  void rejectsUnknownFieldsAndInvalidOperations() {
    String json = codec.encode(patch());

    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(json.replaceFirst("\\{", "{\"legacy\":true,")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                json.replaceFirst("\"baseVersion\":4", "\"baseVersion\":4,\"baseVersion\":4")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(json.replaceFirst("\"UPSERT\"", "\"BROKEN\"")));
  }

  private static CanvasPatch patch() {
    CanvasTransform transform = new CanvasTransform(-10.5, 20.25, 320.5, 260.25);
    CanvasGroup group = new CanvasGroup(GROUP, CANVAS, "group", transform);
    CanvasResource textResource =
        new CanvasResource(TEXT_RESOURCE, CANVAS, TEXT_NODE, 0, null, "note.md", "hello", NOW);
    CanvasResourceNode textNode =
        new CanvasResourceNode(
            TEXT_NODE, CANVAS, "note", transform, GROUP, List.of(textResource), null, null);
    CanvasResource blobResource =
        new CanvasResource(BLOB_RESOURCE, CANVAS, FUNCTION_NODE, 0, BLOB, "image.png", null, NOW);
    CanvasFunction function = new CanvasFunction("fake-image", "{\"prompt\":\"cat\"}");
    CanvasFunctionRun run =
        new CanvasFunctionRun(
            FUNCTION_NODE,
            REQUEST,
            CanvasFunctionRunStatus.RUNNING,
            "QUEUED",
            "{\"stage\":\"QUEUED\"}",
            null,
            NOW);
    CanvasResourceNode functionNode =
        new CanvasResourceNode(
            FUNCTION_NODE,
            CANVAS,
            "generator",
            transform,
            null,
            List.of(blobResource),
            function,
            run);
    return new CanvasPatch(
        4L,
        5L,
        List.of(new CanvasGroupPatch.Upsert(group), new CanvasGroupPatch.Remove(uuid(10))),
        List.of(
            new CanvasNodePatch.Upsert(textNode),
            new CanvasNodePatch.Upsert(functionNode),
            new CanvasNodePatch.Remove(REMOVED_NODE)),
        List.of(
            new CanvasLinkPatch.Upsert(new CanvasLink(CANVAS, TEXT_NODE, FUNCTION_NODE)),
            new CanvasLinkPatch.Remove(FUNCTION_NODE, TEXT_NODE)));
  }

  private static UUID uuid(long value) {
    return new UUID(0L, value);
  }
}
