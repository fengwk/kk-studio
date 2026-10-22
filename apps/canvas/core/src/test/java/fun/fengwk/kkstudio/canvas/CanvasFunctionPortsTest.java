package fun.fengwk.kkstudio.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBinding;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenOutput;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionSubmitState;
import fun.fengwk.kkstudio.canvas.function.CanvasJson;
import fun.fengwk.kkstudio.canvas.function.CanvasResourceKind;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Canvas Function 内部端口（Run 异常、人工决议、Blob 事实与 typed state codec 默认行为）的契约。 */
class CanvasFunctionPortsTest {

  private static final UUID CANVAS_ID = new UUID(0L, 10);
  private static final UUID NODE_ID = new UUID(0L, 20);
  private static final UUID REQUEST_ID = new UUID(0L, 30);
  private static final UUID TARGET_ID = new UUID(0L, 40);
  private static final UUID BLOB_ID = new UUID(0L, 50);

  /** BlobFacts 冻结 blob identity 与媒体事实，blobId 为 null 即拒绝。 */
  @Test
  void blobFactsFreezeFactsAndRejectNullBlobId() {
    CanvasFunctionBlobAccess.BlobFacts facts =
        new CanvasFunctionBlobAccess.BlobFacts(BLOB_ID, "image/png", 1024L, 800L, 600L, null);

    assertEquals(BLOB_ID, facts.blobId());
    assertEquals("image/png", facts.mediaType());
    assertEquals(1024L, facts.sizeBytes());
    assertEquals(800L, facts.width());
    assertEquals(600L, facts.height());
    assertNull(facts.durationMs());

    assertThrows(
        NullPointerException.class,
        () -> new CanvasFunctionBlobAccess.BlobFacts(null, "image/png", 1L, null, null, null));
  }

  /** default checkpoint 原样保留每种 submitState，并把同一 run/stage/adapterState 交给 transition。 */
  @Test
  void defaultCheckpointPreservesSubmitStateAndDelegatesToTransition() {
    for (CanvasFunctionSubmitState submitState : CanvasFunctionSubmitState.values()) {
      CanvasFunctionFrozenRun run = frozenRun(submitState);
      CanvasFunctionFrozenRun transitioned = frozenRun(CanvasFunctionSubmitState.SUBMITTED);
      Map<String, Object> adapterState = Map.of("step", "x");
      RecordingStateCodec codec = new RecordingStateCodec(transitioned);

      CanvasFunctionFrozenRun result = codec.checkpoint(run, "stage-7", adapterState);

      assertSame(run, codec.transitionedRun);
      assertEquals(submitState, codec.transitionedSubmitState);
      assertEquals("stage-7", codec.transitionedStage);
      assertSame(adapterState, codec.transitionedAdapterState);
      assertSame(transitioned, result);
    }
  }

  /** run 异常携带公开 reason 与消息。 */
  @Test
  void runExceptionCarriesPublicReason() {
    CanvasFunctionRunException notFound =
        new CanvasFunctionRunException(CanvasFunctionRunException.Reason.NOT_FOUND, "missing");
    assertEquals(CanvasFunctionRunException.Reason.NOT_FOUND, notFound.reason());
    assertEquals("missing", notFound.getMessage());
  }

  /** 人工决议枚举是 UNKNOWN Run 的唯一处置集合。 */
  @Test
  void unknownResolutionEnumeratesAllOutcomes() {
    assertEquals(
        List.of(
            CanvasFunctionUnknownResolution.RESUME,
            CanvasFunctionUnknownResolution.FAILED,
            CanvasFunctionUnknownResolution.CANCELLED),
        List.of(CanvasFunctionUnknownResolution.values()));
  }

  private static CanvasFunctionFrozenRun frozenRun(CanvasFunctionSubmitState submitState) {
    CanvasFunctionDefinition definition =
        CanvasFunctionDefinition.of(
            "contract-fn",
            "contract-fn",
            CanvasJson.parseObject(
                "{\"type\":\"object\",\"properties\":{},\"required\":[],"
                    + "\"additionalProperties\":false}"),
            CanvasResourceKind.IMAGE,
            new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()));
    return new CanvasFunctionFrozenRun(
        CANVAS_ID,
        NODE_ID,
        "node",
        REQUEST_ID,
        new CanvasFunctionBinding(definition),
        CanvasJson.parseObject("{}"),
        List.of(),
        List.of(new CanvasFunctionFrozenOutput(TARGET_ID, 0, CanvasResourceKind.IMAGE, "out.png")),
        submitState,
        "stage-0",
        Map.of("k", "v"));
  }

  /** 只实现被测 default 依赖的 transition；其余入口抛出以暴露误调用。 */
  private static final class RecordingStateCodec implements CanvasFunctionRunStateCodecPort {

    private final CanvasFunctionFrozenRun transitionResult;
    private CanvasFunctionFrozenRun transitionedRun;
    private CanvasFunctionSubmitState transitionedSubmitState;
    private String transitionedStage;
    private Map<String, Object> transitionedAdapterState;

    private RecordingStateCodec(CanvasFunctionFrozenRun transitionResult) {
      this.transitionResult = transitionResult;
    }

    @Override
    public CanvasFunctionFrozenRun transition(
        CanvasFunctionFrozenRun run,
        CanvasFunctionSubmitState submitState,
        String stage,
        Map<String, Object> adapterState) {
      this.transitionedRun = run;
      this.transitionedSubmitState = submitState;
      this.transitionedStage = stage;
      this.transitionedAdapterState = adapterState;
      return transitionResult;
    }

    @Override
    public String initial(CanvasFunctionFrozenRun run) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String encode(CanvasFunctionFrozenRun run) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CanvasFunctionFrozenRun decode(String json) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String stage(String json) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String functionName(String json) {
      throw new UnsupportedOperationException();
    }
  }
}
