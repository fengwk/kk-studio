package fun.fengwk.kkstudio.canvas.infra.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.BlobFacts;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBinding;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenOutput;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionOutputSpec;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionSubmitState;
import fun.fengwk.kkstudio.canvas.function.CanvasJson;
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.function.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.infra.platform.PlatformCanvasFunctionBlobAccess;
import fun.fengwk.kkstudio.canvas.infra.postgresql.PostgresqlCanvasResourceRepository;
import fun.fengwk.kkstudio.canvas.infra.postgresql.PostgresqlCanvasStore;
import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 从 {@link CanvasFunctionRunTransactions} 拆出的协作者的拒绝分支。
 *
 * <p>这些规则原本由 {@code CanvasFunctionRunTransactions} 的集成测试间接覆盖；拆成独立协作者后按异常逐条直测，避免分母缩小后覆盖门禁失效。
 */
class CanvasFunctionRunCollaboratorsTest {

  private static final UUID CANVAS = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID NODE = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
  private static final UUID SOURCE = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
  private static final UUID BLOB = UUID.fromString("00000000-0000-0000-0000-0000000000a4");
  private static final UUID RESOURCE = UUID.fromString("00000000-0000-0000-0000-0000000000a5");
  private static final UUID OUTPUT = UUID.fromString("00000000-0000-0000-0000-0000000000a6");

  /** 引用源节点不存在时拒绝，而不是把空清单冻结进计划。 */
  @Test
  void freezeManifestRejectsMissingSourceNode() {
    Fixture fixture = new Fixture();
    when(fixture.canvasStore.findNode(CANVAS, SOURCE)).thenReturn(Optional.empty());

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> fixture.plan.freezeManifest(CANVAS, "test.image", referenceArgs(), policy(1)));

    assertEquals("referenced source node must belong to the same canvas", error.getMessage());
  }

  /** 引用位置超出源节点资源数组时拒绝。 */
  @Test
  void freezeManifestRejectsIndexOutsideSourceResources() {
    Fixture fixture = new Fixture();
    when(fixture.canvasStore.findNode(CANVAS, SOURCE)).thenReturn(Optional.of(mock()));
    when(fixture.resourceRepository.findByOwnerNode(CANVAS, SOURCE)).thenReturn(List.of());

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> fixture.plan.freezeManifest(CANVAS, "test.image", referenceArgs(), policy(1)));

    assertEquals("reference index is outside source node resources", error.getMessage());
  }

  /** TEXT 资源没有 blob，不能作为媒体输入被引用。 */
  @Test
  void freezeManifestRejectsTextResource() {
    Fixture fixture = new Fixture();
    when(fixture.canvasStore.findNode(CANVAS, SOURCE)).thenReturn(Optional.of(mock()));
    when(fixture.resourceRepository.findByOwnerNode(CANVAS, SOURCE))
        .thenReturn(List.of(textResource()));

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> fixture.plan.freezeManifest(CANVAS, "test.image", referenceArgs(), policy(1)));

    assertEquals("referenced resource must be a Storage blob", error.getMessage());
  }

  /** Blob 行缺失时拒绝，冻结清单不能携带不存在的媒体事实。 */
  @Test
  void freezeManifestRejectsMissingBlobFacts() {
    Fixture fixture = new Fixture();
    when(fixture.canvasStore.findNode(CANVAS, SOURCE)).thenReturn(Optional.of(mock()));
    when(fixture.resourceRepository.findByOwnerNode(CANVAS, SOURCE))
        .thenReturn(List.of(blobResource()));
    when(fixture.blobAccess.findFacts(BLOB)).thenReturn(Optional.empty());

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> fixture.plan.freezeManifest(CANVAS, "test.image", referenceArgs(), policy(1)));

    assertEquals("referenced blob is missing: " + BLOB, error.getMessage());
  }

  /** 引用数量超过 maxReferences 时拒绝。 */
  @Test
  void freezeManifestRejectsExcessReferences() {
    Fixture fixture = new Fixture();
    stubResolvableReference(fixture);

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> fixture.plan.freezeManifest(CANVAS, "test.image", referenceArgs(), policy(0)));

    assertEquals("reference manifest exceeds maxReferences=0", error.getMessage());
  }

  /** 引用类型不在函数声明的 allowedKinds 内时拒绝。 */
  @Test
  void freezeManifestRejectsDisallowedKind() {
    Fixture fixture = new Fixture();
    stubResolvableReference(fixture);
    CanvasFunctionReferencePolicy onlyVideo =
        new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.VIDEO), 1, Map.of());

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> fixture.plan.freezeManifest(CANVAS, "test.image", referenceArgs(), onlyVideo));

    assertEquals("reference kind is not allowed by function: IMAGE", error.getMessage());
  }

  /** 单类引用超过 maxByKind 时拒绝。 */
  @Test
  void freezeManifestRejectsKindBeyondPerKindLimit() {
    Fixture fixture = new Fixture();
    stubResolvableReference(fixture);
    CanvasFunctionReferencePolicy capped =
        new CanvasFunctionReferencePolicy(
            Set.of(CanvasResourceKind.IMAGE), 1, Map.of(CanvasResourceKind.IMAGE, 0));

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> fixture.plan.freezeManifest(CANVAS, "test.image", referenceArgs(), capped));

    assertEquals("reference kind exceeds function limit: IMAGE", error.getMessage());
  }

  /** 解析成功后冻结清单携带资源与 Blob 事实；输出计划按定义顺序预分配 id。 */
  @Test
  void freezeManifestAndOutputsProduceTheFrozenPlan() {
    Fixture fixture = new Fixture();
    stubResolvableReference(fixture);

    List<CanvasFunctionFrozenReference> manifest =
        fixture.plan.freezeManifest(CANVAS, "test.image", referenceArgs(), policy(1));

    assertEquals(1, manifest.size());
    assertEquals(RESOURCE, manifest.get(0).resourceId());
    assertEquals(CanvasResourceKind.IMAGE, manifest.get(0).kind());
    assertEquals("image/png", manifest.get(0).mediaType());

    List<CanvasFunctionFrozenOutput> outputs =
        CanvasFunctionRunPlan.freezeOutputs("output", definition());
    assertEquals(1, outputs.size());
    assertEquals(0, outputs.get(0).index());
  }

  /** 显式名与派生名在具体节点名上撞名时，启动期必须以 400 校验错误拒绝，而不是落到 500。 */
  @Test
  void freezeOutputsRejectsNodeDependentNameCollisionsAsValidation() {
    CanvasFunctionDefinition collidesOnNodeName =
        new CanvasFunctionDefinition(
            "test.collision",
            "Collision",
            CanvasJson.parseObject(
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"),
            List.of(
                CanvasFunctionOutputSpec.named(CanvasResourceKind.IMAGE, "output.png"),
                CanvasFunctionOutputSpec.of(CanvasResourceKind.IMAGE)),
            new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 0, Map.of()));

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> CanvasFunctionRunPlan.freezeOutputs("output", collidesOnNodeName));

    assertEquals(DomainErrorCode.VALIDATION, error.code());
    assertTrue(error.getMessage().contains("output.png"), error.getMessage());
  }

  /** 未物化的槽位（无 Resource 行）不能发布。 */
  @Test
  void requireMaterializedOutputRejectsMissingResource() {
    Fixture fixture = new Fixture();
    when(fixture.resourceRepository.findById(CANVAS, OUTPUT)).thenReturn(Optional.empty());

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> fixture.outputs.requireMaterializedOutput(frozenRun(), plannedOutput()));

    assertTrue(error.getMessage().contains("must be materialized as an unowned"));
  }

  /** 已挂接到节点的 Resource 不是物化产物，不能发布。 */
  @Test
  void requireMaterializedOutputRejectsOwnedResource() {
    Fixture fixture = new Fixture();
    when(fixture.resourceRepository.findById(CANVAS, OUTPUT))
        .thenReturn(
            Optional.of(
                new CanvasResource(
                    OUTPUT,
                    CANVAS,
                    NODE,
                    0,
                    BLOB,
                    "out.png",
                    null,
                    Instant.parse("2026-01-01T00:00:00Z"))));

    assertThrows(
        IllegalArgumentException.class,
        () -> fixture.outputs.requireMaterializedOutput(frozenRun(), plannedOutput()));
  }

  /** TEXT 槽位必须物化为内联文本，不能是 blob。 */
  @Test
  void requireMaterializedOutputRejectsBlobInTextSlot() {
    Fixture fixture = new Fixture();
    when(fixture.resourceRepository.findById(CANVAS, OUTPUT))
        .thenReturn(Optional.of(unowned(OUTPUT, BLOB, null)));

    assertThrows(
        IllegalArgumentException.class,
        () -> fixture.outputs.requireMaterializedOutput(textRun(), textOutput()));
  }

  /** 媒体槽位必须是 blob，不能是内联文本。 */
  @Test
  void requireMaterializedOutputRejectsTextInMediaSlot() {
    Fixture fixture = new Fixture();
    when(fixture.resourceRepository.findById(CANVAS, OUTPUT))
        .thenReturn(Optional.of(unowned(OUTPUT, null, "text")));

    assertThrows(
        IllegalArgumentException.class,
        () -> fixture.outputs.requireMaterializedOutput(frozenRun(), plannedOutput()));
  }

  /** Blob 类型与冻结槽位不一致时拒绝发布。 */
  @Test
  void requireMaterializedOutputRejectsMismatchedBlobKind() {
    Fixture fixture = new Fixture();
    when(fixture.resourceRepository.findById(CANVAS, OUTPUT))
        .thenReturn(Optional.of(unowned(OUTPUT, BLOB, null)));
    when(fixture.blobAccess.findFacts(BLOB)).thenReturn(Optional.of(blobFacts("video/mp4")));

    assertThrows(
        IllegalArgumentException.class,
        () -> fixture.outputs.requireMaterializedOutput(frozenRun(), plannedOutput()));
  }

  /** 正确物化的 media 槽位通过校验。 */
  @Test
  void requireMaterializedOutputAcceptsMatchingBlobKind() {
    Fixture fixture = new Fixture();
    when(fixture.resourceRepository.findById(CANVAS, OUTPUT))
        .thenReturn(Optional.of(unowned(OUTPUT, BLOB, null)));
    when(fixture.blobAccess.findFacts(BLOB)).thenReturn(Optional.of(blobFacts("image/png")));

    fixture.outputs.requireMaterializedOutput(frozenRun(), plannedOutput());
  }

  /** UNKNOWN reason 为空白时以运行时异常拒绝（Worker 侧说明，不是客户端校验）。 */
  @Test
  void validateUnknownReasonRejectsBlankText() {
    assertThrows(
        IllegalArgumentException.class, () -> CanvasFunctionRunTexts.validateUnknownReason(null));
    assertThrows(
        IllegalArgumentException.class, () -> CanvasFunctionRunTexts.validateUnknownReason("  "));
    assertThrows(
        IllegalArgumentException.class, () -> CanvasFunctionRunTexts.validateUnknownReason("\n\t"));
  }

  /** reason 去控制字符后按列宽截断，不抛异常。 */
  @Test
  void validateUnknownReasonStripsAndTruncates() {
    assertEquals("a b", CanvasFunctionRunTexts.validateUnknownReason(" a\tb "));
    String longReason = "x".repeat(2000);
    assertEquals(1024, CanvasFunctionRunTexts.validateUnknownReason(longReason).length());
  }

  /** 核查事实缺失或全为控制字符时是客户端校验错误。 */
  @Test
  void validateVerificationRejectsBlankText() {
    DomainException missing =
        assertThrows(
            DomainException.class, () -> CanvasFunctionRunTexts.validateVerification(null));
    assertEquals("verification must not be blank", missing.getMessage());
    assertThrows(DomainException.class, () -> CanvasFunctionRunTexts.validateVerification("   "));
    assertThrows(DomainException.class, () -> CanvasFunctionRunTexts.validateVerification("\n\t"));
  }

  /** 核查事实去控制字符后按列宽截断，不抛异常。 */
  @Test
  void validateVerificationStripsAndTruncates() {
    assertEquals("a b", CanvasFunctionRunTexts.validateVerification(" a\tb "));
    String longVerification = "y".repeat(2000);
    assertEquals(1024, CanvasFunctionRunTexts.validateVerification(longVerification).length());
  }

  /** 两类领域错误共用 canvas_function_run 资源名，冲突原因固定。 */
  @Test
  void errorsExposeStableResourceAndReason() {
    assertEquals("canvas_function_run", CanvasFunctionRunErrors.notFound("x").resource());
    assertEquals("FUNCTION_RUN_CONFLICT", CanvasFunctionRunErrors.conflict("x").reason());
  }

  // --- fixtures ---

  private static final class Fixture {

    private final PostgresqlCanvasStore canvasStore = mock(PostgresqlCanvasStore.class);
    private final PostgresqlCanvasResourceRepository resourceRepository =
        mock(PostgresqlCanvasResourceRepository.class);
    private final PlatformCanvasFunctionBlobAccess blobAccess =
        mock(PlatformCanvasFunctionBlobAccess.class);
    private final CanvasFunctionRunPlan plan =
        new CanvasFunctionRunPlan(canvasStore, resourceRepository, blobAccess);
    private final CanvasFunctionRunOutputs outputs =
        new CanvasFunctionRunOutputs(resourceRepository, blobAccess);
  }

  /** 一个可解析的 IMAGE 引用：源节点存在、槽位 0 是 blob、Blob 事实齐备。 */
  private static void stubResolvableReference(Fixture fixture) {
    when(fixture.canvasStore.findNode(CANVAS, SOURCE)).thenReturn(Optional.of(mock()));
    when(fixture.resourceRepository.findByOwnerNode(CANVAS, SOURCE))
        .thenReturn(List.of(blobResource()));
    when(fixture.blobAccess.findFacts(BLOB)).thenReturn(Optional.of(blobFacts("image/png")));
  }

  private static CanvasResource blobResource() {
    return new CanvasResource(
        RESOURCE, CANVAS, SOURCE, 0, BLOB, "in.png", null, Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static CanvasResource textResource() {
    return new CanvasResource(
        RESOURCE, CANVAS, SOURCE, 0, null, "in.txt", "body", Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static CanvasResource unowned(UUID id, UUID blobId, String textContent) {
    return new CanvasResource(
        id, CANVAS, null, null, blobId, "out", textContent, Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static BlobFacts blobFacts(String mediaType) {
    return new BlobFacts(BLOB, mediaType, 3L, 1L, 1L, null);
  }

  private static JsonObject referenceArgs() {
    return CanvasJson.parseObject(
        "{\"source\":{\"type\":\"resource\",\"nodeId\":\"" + SOURCE + "\",\"index\":0}}");
  }

  private static CanvasFunctionReferencePolicy policy(int maxReferences) {
    return new CanvasFunctionReferencePolicy(
        Set.of(CanvasResourceKind.IMAGE), maxReferences, Map.of());
  }

  private static CanvasFunctionDefinition definition() {
    return new CanvasFunctionDefinition(
        "test.image",
        "Test Image",
        CanvasJson.parseObject(
            "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"),
        List.of(CanvasFunctionOutputSpec.named(CanvasResourceKind.IMAGE, "out.png")),
        new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 0, Map.of()));
  }

  private static CanvasFunctionFrozenRun frozenRun() {
    return frozenRun(CanvasResourceKind.IMAGE);
  }

  private static CanvasFunctionFrozenRun textRun() {
    return frozenRun(CanvasResourceKind.TEXT);
  }

  private static CanvasFunctionFrozenRun frozenRun(CanvasResourceKind kind) {
    CanvasFunctionDefinition definition =
        new CanvasFunctionDefinition(
            "test.image",
            "Test Image",
            CanvasJson.parseObject(
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"),
            List.of(
                new CanvasFunctionOutputSpec(
                    kind, kind == CanvasResourceKind.TEXT ? "out.txt" : "out.png")),
            new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 0, Map.of()));
    return new CanvasFunctionFrozenRun(
        CANVAS,
        NODE,
        "output",
        UUID.fromString("00000000-0000-0000-0000-0000000000a7"),
        new CanvasFunctionBinding(definition),
        CanvasJson.parseObject("{}"),
        List.of(),
        List.of(
            new CanvasFunctionFrozenOutput(
                OUTPUT, 0, kind, kind == CanvasResourceKind.TEXT ? "out.txt" : "out.png")),
        CanvasFunctionSubmitState.PENDING,
        "QUEUED",
        Map.of());
  }

  private static CanvasFunctionFrozenOutput plannedOutput() {
    return new CanvasFunctionFrozenOutput(OUTPUT, 0, CanvasResourceKind.IMAGE, "out.png");
  }

  private static CanvasFunctionFrozenOutput textOutput() {
    return new CanvasFunctionFrozenOutput(OUTPUT, 0, CanvasResourceKind.TEXT, "out.txt");
  }
}
