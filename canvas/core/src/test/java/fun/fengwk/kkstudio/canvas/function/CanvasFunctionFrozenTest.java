package fun.fengwk.kkstudio.canvas.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Function 冻结计划携带 UUID 身份与权威 blob 媒体事实快照。 */
class CanvasFunctionFrozenTest {

  private static final UUID NODE_ID = new UUID(0L, 20);
  private static final UUID CANVAS_ID = new UUID(0L, 10);
  private static final UUID REQUEST_ID = new UUID(0L, 30);
  private static final UUID RESOURCE_ID = new UUID(0L, 1);
  private static final UUID BLOB_ID = new UUID(0L, 99);
  private static final UUID TARGET_ID = new UUID(0L, 100);

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
            800L,
            600L,
            null);
    assertEquals(RESOURCE_ID, reference.resourceId());
    assertEquals(BLOB_ID, reference.blobId());
    assertEquals(CanvasResourceKind.IMAGE, reference.kind());
    assertEquals(1024L, reference.sizeBytes());
    assertEquals(800L, reference.width());
    assertEquals(600L, reference.height());

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
                null,
                null,
                null));
    assertThrows(
        NullPointerException.class,
        () ->
            new CanvasFunctionFrozenReference(
                NODE_ID,
                0,
                null,
                BLOB_ID,
                CanvasResourceKind.IMAGE,
                "a",
                "image/png",
                1,
                null,
                null,
                null));
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
                null,
                null,
                null));
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
                null,
                null,
                null));
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
                null,
                null,
                null));
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
                1,
                800L,
                null,
                null));
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
                1,
                0L,
                600L,
                null));
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
                1,
                null,
                null,
                0L));
  }

  /** 冻结计划承载 UUID 身份、提交事实与有界输出计划：槽位顺序、类型必须与定义一致，名称与 Resource ID 不得重复。 */
  @Test
  void frozenRunCarriesIdentitySubmitFactAndFrozenOutputPlan() {
    CanvasFunctionDefinition definition = CanvasFunctionCatalogTest.function("fake-image");
    CanvasFunctionFrozenRun run =
        run(definition, List.of(frozenOutput(0)), CanvasFunctionSubmitState.PENDING);
    assertEquals(NODE_ID, run.nodeId());
    assertEquals(REQUEST_ID, run.requestId());
    assertFalse(run.submitted());
    assertEquals(List.of(TARGET_ID), run.outputResourceIds());
    assertEquals(TARGET_ID, run.output(0).resourceId());
    assertFalse(run.output(0).inlineText());
    assertTrue(
        run(definition, List.of(frozenOutput(0)), CanvasFunctionSubmitState.SUBMITTED).submitted());

    assertThrows(
        NullPointerException.class,
        () -> run(null, List.of(frozenOutput(0)), CanvasFunctionSubmitState.PENDING));
    assertThrows(NullPointerException.class, () -> run(definition, List.of(frozenOutput(0)), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> run(definition, List.of(), CanvasFunctionSubmitState.PENDING));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(
                definition,
                List.of(
                    new CanvasFunctionFrozenOutput(
                        TARGET_ID, 1, CanvasResourceKind.IMAGE, "out.png")),
                CanvasFunctionSubmitState.PENDING));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(
                definition,
                List.of(
                    new CanvasFunctionFrozenOutput(
                        TARGET_ID, 0, CanvasResourceKind.VIDEO, "out.mp4")),
                CanvasFunctionSubmitState.PENDING));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(
                definition,
                List.of(
                    new CanvasFunctionFrozenOutput(TARGET_ID, 0, CanvasResourceKind.IMAGE, " ")),
                CanvasFunctionSubmitState.PENDING));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(
                definition,
                List.of(
                    new CanvasFunctionFrozenOutput(
                        TARGET_ID, 1, CanvasResourceKind.IMAGE, "out.png")),
                CanvasFunctionSubmitState.PENDING));
    assertThrows(
        NullPointerException.class, () -> run(definition, null, CanvasFunctionSubmitState.PENDING));
  }

  /** 多输出计划必须唯一命名、唯一预分配 Resource ID，且 INDEX 与声明顺序一致。 */
  @Test
  void frozenRunRejectsDuplicateNamesResourceIdsAndOutOfOrderSlots() {
    CanvasFunctionDefinition definition = multiOutputDefinition();
    List<CanvasFunctionFrozenOutput> outputs =
        List.of(
            new CanvasFunctionFrozenOutput(TARGET_ID, 0, CanvasResourceKind.TEXT, "report.txt"),
            new CanvasFunctionFrozenOutput(
                new UUID(0L, 101), 1, CanvasResourceKind.IMAGE, "chart.png"));
    CanvasFunctionFrozenRun run =
        new CanvasFunctionFrozenRun(
            CANVAS_ID,
            NODE_ID,
            "fn",
            REQUEST_ID,
            definition,
            CanvasJson.parseObject("{}"),
            List.of(),
            outputs,
            CanvasFunctionSubmitState.PENDING,
            "STARTED",
            Map.of());
    assertEquals(List.of(TARGET_ID, new UUID(0L, 101)), run.outputResourceIds());
    assertTrue(run.output(0).inlineText());
    assertFalse(run.output(1).inlineText());
    assertEquals("chart.png", run.output(1).name());
    assertThrows(IllegalArgumentException.class, () -> run.output(-1));
    assertThrows(IllegalArgumentException.class, () -> run.output(2));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(
                definition,
                List.of(
                    new CanvasFunctionFrozenOutput(
                        TARGET_ID, 0, CanvasResourceKind.TEXT, "report.txt"),
                    new CanvasFunctionFrozenOutput(
                        new UUID(0L, 101), 1, CanvasResourceKind.IMAGE, "report.txt")),
                CanvasFunctionSubmitState.PENDING));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(
                definition,
                List.of(
                    new CanvasFunctionFrozenOutput(
                        TARGET_ID, 0, CanvasResourceKind.TEXT, "report.txt"),
                    new CanvasFunctionFrozenOutput(
                        TARGET_ID, 1, CanvasResourceKind.IMAGE, "a.png")),
                CanvasFunctionSubmitState.PENDING));
  }

  /** 单一入口：槽位数量必须与定义输出计划逐位对应。 */
  private static CanvasFunctionFrozenRun run(
      CanvasFunctionDefinition definition,
      List<CanvasFunctionFrozenOutput> outputs,
      CanvasFunctionSubmitState submitState) {
    return new CanvasFunctionFrozenRun(
        CANVAS_ID,
        NODE_ID,
        "fn",
        REQUEST_ID,
        definition,
        CanvasJson.parseObject("{}"),
        List.of(),
        outputs,
        submitState,
        "STARTED",
        Map.of());
  }

  private static CanvasFunctionFrozenOutput frozenOutput(int index) {
    return new CanvasFunctionFrozenOutput(TARGET_ID, index, CanvasResourceKind.IMAGE, "out.png");
  }

  private static CanvasFunctionDefinition multiOutputDefinition() {
    return new CanvasFunctionDefinition(
        "fake.report",
        "report",
        CanvasFunctionDefinitionTest.schema(),
        List.of(
            CanvasFunctionOutputSpec.named(CanvasResourceKind.TEXT, "report.txt"),
            CanvasFunctionOutputSpec.named(CanvasResourceKind.IMAGE, "chart.png")),
        CanvasFunctionDefinitionTest.policy());
  }
}
