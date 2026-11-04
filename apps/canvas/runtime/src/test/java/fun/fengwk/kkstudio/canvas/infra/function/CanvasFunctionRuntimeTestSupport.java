package fun.fengwk.kkstudio.canvas.infra.function;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.canvas.BlobFacts;
import fun.fengwk.kkstudio.canvas.CanvasFunction;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePin;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionVersion;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.canvas.NodeRecord;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenOutput;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionOutputSpec;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasJson;
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.function.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.infra.TestBlobAccess;
import fun.fengwk.kkstudio.canvas.infra.platform.PluginCanvasFunctionCatalogSource;
import fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasFunctionWorkStore;
import fun.fengwk.kkstudio.canvas.infra.postgresql.PostgresCanvasInfraTestSupport;
import fun.fengwk.kkstudio.canvas.infra.postgresql.PostgresqlCanvasFunctionResourcePinRepository;
import fun.fengwk.kkstudio.canvas.infra.postgresql.PostgresqlCanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.testing.TestCanvasFunctionCatalogSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Canvas Function Runtime 的真实 PostgreSQL 测试基座。
 *
 * <p>按不变量拆分的测试共享同一组冻结函数定义、真实仓库与事务协作者、目录替身和资源生命周期替身；每个测试类只声明自己的前置条件与断言。 测试目录里的 adapter
 * 只用于冻结目录与围栏，从不执行真实提交。
 */
abstract class CanvasFunctionRuntimeTestSupport extends PostgresCanvasInfraTestSupport {

  static final String REQUEST_1 = "00000000-0000-0000-0000-000000000101";
  static final String REQUEST_2 = "00000000-0000-0000-0000-000000000102";
  static final CanvasTransform TRANSFORM = new CanvasTransform(0, 0, 100, 80);
  static final JsonObject ARGS_SCHEMA =
      CanvasJson.parseObject(
          """
          {
            "type": "object",
            "additionalProperties": false,
            "required": ["prompt"],
            "properties": {
              "prompt": { "type": "string" },
              "ratio": { "type": "string", "enum": ["16:9", "1:1"], "default": "16:9" },
              "source": { "type": "resourceReference" }
            }
          }
          """);
  static final CanvasFunctionDefinition DEFINITION =
      CanvasFunctionDefinition.of(
          "test.image",
          "Test Image",
          ARGS_SCHEMA,
          CanvasResourceKind.IMAGE,
          new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()));
  static final CanvasFunctionDefinition MULTI_MEDIA_DEFINITION =
      new CanvasFunctionDefinition(
          "test.multimedia",
          "Test Multimedia",
          ARGS_SCHEMA,
          List.of(
              CanvasFunctionOutputSpec.named(CanvasResourceKind.IMAGE, "preview.png"),
              CanvasFunctionOutputSpec.named(CanvasResourceKind.VIDEO, "video.mp4")),
          new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()));
  static final CanvasFunctionDefinition TEXT_IMAGE_DEFINITION =
      new CanvasFunctionDefinition(
          "test.textimage",
          "Test Text and Image",
          ARGS_SCHEMA,
          List.of(
              CanvasFunctionOutputSpec.named(CanvasResourceKind.TEXT, "summary.txt"),
              CanvasFunctionOutputSpec.named(CanvasResourceKind.IMAGE, "preview.png")),
          new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()));
  static final CanvasFunctionDefinition TWO_IMAGES_DEFINITION =
      new CanvasFunctionDefinition(
          "test.twoimages",
          "Test Two Images",
          ARGS_SCHEMA,
          List.of(
              CanvasFunctionOutputSpec.named(CanvasResourceKind.IMAGE, "first.png"),
              CanvasFunctionOutputSpec.named(CanvasResourceKind.IMAGE, "second.png")),
          new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()));

  @Autowired protected PostgresqlCanvasFunctionRunRepository runRepository;
  @Autowired protected PostgresqlCanvasFunctionResourcePinRepository pinRepository;
  @Autowired protected TestCanvasFunctionCatalogSource catalogSource;
  @Autowired protected CanvasFunctionWorkStore workStore;
  @Autowired protected PlatformTransactionManager transactionManager;
  @Autowired protected TestBlobAccess blobAccess;
  @Autowired private CanvasFunctionArgsCodec argsCodec;
  @Autowired private CanvasFunctionRunStateCodec stateCodec;
  @Autowired private Clock clock;

  protected CanvasFunctionRunTransactions runtimeTransactions;

  /** Runtime foundation 使用模块内的忠实 pin/owner 双替身，避免与实际 Blob ref_count 端口耦合。 */
  @BeforeEach
  void buildRuntimeTransactions() {
    runtimeTransactions = transactionsFor(catalogSource.source());
  }

  /** 用给定 source 构造 Runtime transactions，复用真实 store/仓库与测试 pin/owner 双替身。 */
  CanvasFunctionRunTransactions transactionsFor(PluginCanvasFunctionCatalogSource catalogSource) {
    return new CanvasFunctionRunTransactions(
        canvasStore,
        resourceRepository,
        runRepository,
        pinRepository,
        catalogSource,
        argsCodec,
        stateCodec,
        new TestCanvasResourceLifecycle(resourceRepository, pinRepository),
        blobAccess.access(),
        clock);
  }

  /** 客户端从目录读到的 fingerprint 与 start 接受的完全对齐。 */
  static CanvasFunctionVersion versionOf(CanvasFunctionDefinition definition) {
    return new CanvasFunctionVersion(CanvasFunctionCatalog.definitionFingerprint(definition));
  }

  /** 当前宿主的目录内容，用于构造自持 source。 */
  CanvasFunctionCatalog frozenCatalog() {
    try (PluginCanvasFunctionCatalogSource.Lease lease = catalogSource.source().acquire()) {
      return lease.catalog();
    }
  }

  /** 构造只声明给定定义的忠实 adapter 目录。 */
  static CanvasFunctionCatalog catalogOf(CanvasFunctionDefinition definition) {
    return catalogOf(new TestCanvasFunctionAdapter(definition));
  }

  /** 包住给定 adapter 的目录，用于断言围栏拒绝时外部副作用（preflight）根本没有发生。 */
  static CanvasFunctionCatalog catalogOf(CanvasFunctionAdapter adapter) {
    return CanvasFunctionCatalog.from(List.of(adapter));
  }

  /**
   * start 的目录租用必须由真实事务的 afterCompletion 释放，因此测试统一经 {@link TransactionTemplate} 调用， 而不是绕过 proxy
   * 直接调用。
   */
  CanvasFunctionStartResult startRun(UUID canvasId, UUID nodeId, String requestId) {
    return startRun(canvasId, nodeId, requestId, currentVersion(canvasId, nodeId));
  }

  /** 显式提交某个 expectedVersion 的 start，用于版本围栏断言。 */
  CanvasFunctionStartResult startRun(
      UUID canvasId, UUID nodeId, String requestId, CanvasFunctionVersion expectedVersion) {
    return transactions.execute(
        status -> runtimeTransactions.start(canvasId, nodeId, requestId, expectedVersion));
  }

  /**
   * 客户端视角的当前目录版本：在真实目录租用内取该节点函数的定义 fingerprint。
   *
   * <p>节点不是 Function 或不存在时退回默认函数，让事务自己报告真正的领域错误，而不是在测试辅助里提前失败。
   */
  CanvasFunctionVersion currentVersion(UUID canvasId, UUID nodeId) {
    String functionName =
        canvasStore
            .findNode(canvasId, nodeId)
            .map(NodeRecord::function)
            .map(CanvasFunction::name)
            .orElse(DEFINITION.name());
    return currentVersion(functionName);
  }

  CanvasFunctionVersion currentVersion(String functionName) {
    try (PluginCanvasFunctionCatalogSource.Lease lease = catalogSource.source().acquire()) {
      return new CanvasFunctionVersion(
          CanvasFunctionCatalog.definitionFingerprint(
              lease.catalog().require(functionName).function()));
    }
  }

  /** start 提交的 availableAt 是 claim 的权威时间；不能用独立 wall clock 跨过同一事务的时间边界。 */
  ClaimedRun claimStartedRun(UUID canvasId, UUID nodeId, String requestId, String ownerToken) {
    CanvasFunctionRun started = startRun(canvasId, nodeId, requestId).run();
    ClaimedRun claim = workStore.claimNext(Duration.ofSeconds(30), ownerToken).orElseThrow();
    assertEquals(nodeId, claim.nodeId(), "claim must correspond to the started node");
    return claim;
  }

  UUID addFunctionNode(UUID canvasId, String name, String config) {
    return addFunctionNode(canvasId, name, DEFINITION.name(), config);
  }

  UUID addFunctionNode(UUID canvasId, String name, String functionName, String config) {
    UUID nodeId = UUID.randomUUID();
    canvasStore.addNode(
        new NodeRecord(
            nodeId,
            canvasId,
            name,
            TRANSFORM,
            null,
            new CanvasFunction(functionName, CanvasJson.parseObject(config))));
    return nodeId;
  }

  UUID addPlainNode(UUID canvasId, String name) {
    UUID nodeId = UUID.randomUUID();
    canvasStore.addNode(new NodeRecord(nodeId, canvasId, name, TRANSFORM, null, null));
    return nodeId;
  }

  /** 宿主物化媒体槽位的忠实双替身：Resource 行与 OUTPUT pin 必须同事务写入，相同 resourceId 幂等返回。 */
  CanvasResource materializeBlobOutput(
      UUID canvasId, UUID nodeId, UUID requestId, CanvasFunctionFrozenOutput output) {
    return transactions.execute(
        status -> {
          CanvasResource existing =
              resourceRepository.findById(canvasId, output.resourceId()).orElse(null);
          if (existing != null) {
            return existing;
          }
          CanvasResource resource =
              addBlobResource(
                  canvasId, null, null, output.resourceId(), output.name(), output.kind());
          pinRepository.addAll(
              List.of(
                  new CanvasFunctionResourcePin(
                      canvasId,
                      nodeId,
                      requestId,
                      output.resourceId(),
                      CanvasFunctionResourcePin.Role.OUTPUT)));
          return resource;
        });
  }

  /** 宿主物化文本槽位的忠实双替身：内容内联在 Resource 行，无 blob，与 OUTPUT pin 同事务写入。 */
  CanvasResource materializeTextOutput(
      UUID canvasId, UUID nodeId, UUID requestId, CanvasFunctionFrozenOutput output, String text) {
    return transactions.execute(
        status -> {
          CanvasResource existing =
              resourceRepository.findById(canvasId, output.resourceId()).orElse(null);
          if (existing != null) {
            return existing;
          }
          CanvasResource resource =
              new CanvasResource(
                  output.resourceId(),
                  canvasId,
                  null,
                  null,
                  null,
                  output.name(),
                  text,
                  Instant.now());
          resourceRepository.add(resource);
          pinRepository.addAll(
              List.of(
                  new CanvasFunctionResourcePin(
                      canvasId,
                      nodeId,
                      requestId,
                      output.resourceId(),
                      CanvasFunctionResourcePin.Role.OUTPUT)));
          return resource;
        });
  }

  CanvasResource materializeTarget(
      UUID canvasId, UUID nodeId, UUID requestId, CanvasFunctionFrozenOutput output) {
    return output.inlineText()
        ? materializeTextOutput(canvasId, nodeId, requestId, output, "text content")
        : materializeBlobOutput(canvasId, nodeId, requestId, output);
  }

  CanvasResource addBlobResource(
      UUID canvasId,
      UUID ownerNodeId,
      Integer resourceIndex,
      UUID resourceId,
      String name,
      CanvasResourceKind kind) {
    UUID blobId = UUID.randomUUID();
    String mediaType =
        switch (kind) {
          case IMAGE -> "image/png";
          case VIDEO -> "video/mp4";
          case AUDIO -> "audio/mp4";
          case TEXT -> "text/plain";
        };
    blobAccess.put(new BlobFacts(blobId, mediaType, 3L, 1L, 1L, null));
    jdbc.update(
        "insert into storage_blob "
            + "(id, sha256, size_bytes, media_type, width, height, ref_count, state) "
            + "values (?, ?, ?, ?, ?, ?, ?, ?)",
        blobId,
        String.format("%064x", blobId.getLeastSignificantBits() & Long.MAX_VALUE),
        3L,
        mediaType,
        1,
        1,
        1L,
        "ACTIVE");
    CanvasResource resource =
        new CanvasResource(
            resourceId,
            canvasId,
            ownerNodeId,
            resourceIndex,
            blobId,
            name != null ? name : "resource.png",
            null,
            Instant.now());
    resourceRepository.add(resource);
    return resource;
  }

  CanvasResource addBlobResource(
      UUID canvasId, UUID ownerNodeId, Integer resourceIndex, UUID resourceId) {
    return addBlobResource(
        canvasId, ownerNodeId, resourceIndex, resourceId, "resource.png", CanvasResourceKind.IMAGE);
  }

  /** 恢复只从持久 state 解码完整 binding，不重查当前目录。 */
  CanvasFunctionFrozenRun decode(CanvasFunctionRun run) {
    return stateCodec.decode(run.stateJson());
  }

  long version(UUID canvasId) {
    return canvasStore.findDocument(canvasId).orElseThrow().revision();
  }

  static String configWithoutReferences() {
    return "{\"prompt\":\"prompt\"}";
  }

  static String configWithReference(UUID sourceNodeId) {
    return "{\"prompt\":\"use reference\",\"source\":{\"type\":\"resource\",\"nodeId\":\""
        + sourceNodeId
        + "\",\"index\":0}}";
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FoundationConfiguration {

    @Bean
    CanvasFunctionAdapter foundationAdapter() {
      return new TestCanvasFunctionAdapter(
          DEFINITION, MULTI_MEDIA_DEFINITION, TEXT_IMAGE_DEFINITION, TWO_IMAGES_DEFINITION);
    }
  }
}
