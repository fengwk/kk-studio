package fun.fengwk.kkstudio.canvas.infra.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig.ReferenceSegment;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig.TextSegment;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionParameterDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Versioned state codec 冻结完整 plan，并严格拒绝 schema、model 与类型漂移。 */
class CanvasFunctionRunStateCodecTest {

  private static final UUID CANVAS = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID NODE = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID SOURCE_NODE = UUID.fromString("00000000-0000-0000-0000-000000000003");
  private static final UUID RESOURCE = UUID.fromString("00000000-0000-0000-0000-000000000004");
  private static final UUID BLOB = UUID.fromString("00000000-0000-0000-0000-000000000005");
  private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-000000000006");
  private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-000000000007");

  private ObjectMapper mapper;
  private CanvasFunctionRunStateCodec codec;
  private CanvasFunctionModel model;
  private CanvasFunctionFrozenRun run;

  @BeforeEach
  void setUp() {
    mapper = new ObjectMapper();
    CanvasFunctionConfigCodec configCodec = new CanvasFunctionConfigCodec(mapper);
    codec = new CanvasFunctionRunStateCodec(mapper, configCodec);
    model =
        new CanvasFunctionModel(
            "test-video",
            "Test Video",
            CanvasResourceKind.VIDEO,
            new CanvasFunctionReferencePolicy(
                Set.of(CanvasResourceKind.IMAGE, CanvasResourceKind.AUDIO),
                3,
                Map.of(CanvasResourceKind.AUDIO, 1)),
            List.of(
                CanvasFunctionParameterDefinition.enumParameter(
                    "ratio", "Ratio", true, null, List.of("16:9", "9:16")),
                CanvasFunctionParameterDefinition.integerParameter(
                    "duration", "Duration", false, 5, 4, 15)));
    run =
        new CanvasFunctionFrozenRun(
            CANVAS,
            NODE,
            "output",
            REQUEST,
            model,
            new CanvasFunctionConfig(
                List.of(
                    new TextSegment("before"),
                    new ReferenceSegment(SOURCE_NODE, 0),
                    new TextSegment("after")),
                Map.of("ratio", "16:9", "duration", 5)),
            List.of(
                new CanvasFunctionFrozenReference(
                    SOURCE_NODE,
                    0,
                    RESOURCE,
                    BLOB,
                    CanvasResourceKind.IMAGE,
                    "source.png",
                    "image/png",
                    12L,
                    640L,
                    480L,
                    null)),
            "output.mp4",
            TARGET,
            "SUBMITTED",
            Map.of("jobId", "job-1", "attempt", 1));
  }

  @Test
  void roundTripsTheTypedFrozenPlan() {
    String json = codec.encode(run);

    assertEquals(run, codec.decode(json, model));
    assertEquals("SUBMITTED", codec.stage(json));
    assertEquals("test-video", codec.modelKey(json));
    assertEquals(json, codec.initial(run));
    assertEquals("POLLING", codec.checkpoint(run, "POLLING", Map.of("jobId", "job-1")).stage());
  }

  @Test
  void rejectsSchemaVersionAndRegistryDrift() throws Exception {
    ObjectNode root = (ObjectNode) mapper.readTree(codec.encode(run));
    root.put("extra", true);
    String unknownField = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(unknownField, model));

    root.remove("extra");
    root.put("version", 3);
    String unsupportedVersion = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(unsupportedVersion, model));

    CanvasFunctionModel otherModel =
        new CanvasFunctionModel(
            "other-video",
            "Other Video",
            CanvasResourceKind.VIDEO,
            model.referencePolicy(),
            model.parameters());
    assertThrows(IllegalArgumentException.class, () -> codec.decode(codec.encode(run), otherModel));
  }

  @Test
  void rejectsMalformedManifestConfigStageAndDuplicateFields() throws Exception {
    ObjectNode root = (ObjectNode) mapper.readTree(codec.encode(run));
    ObjectNode plan = (ObjectNode) root.get("plan");
    ObjectNode reference = (ObjectNode) ((ArrayNode) plan.get("manifest")).get(0);
    reference.put("sourceIndex", -1);
    String invalidManifest = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(invalidManifest, model));

    root = (ObjectNode) mapper.readTree(codec.encode(run));
    root.put("stage", "polling");
    String invalidStage = root.toString();
    assertThrows(IllegalArgumentException.class, () -> codec.decode(invalidStage, model));

    String duplicate =
        codec.encode(run).replaceFirst("\"version\":2", "\"version\":2,\"version\":2");
    assertThrows(IllegalArgumentException.class, () -> codec.decode(duplicate, model));
    assertThrows(IllegalArgumentException.class, () -> codec.stage("{"));
    assertThrows(IllegalArgumentException.class, () -> codec.modelKey("{}"));
  }
}
