package fun.fengwk.kkstudio.canvas.infra.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePin;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePinRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasLink;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.CanvasResourceLifecycle;
import fun.fengwk.kkstudio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.canvas.CanvasStore.NodeRecord;
import fun.fengwk.kkstudio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBlobAccess;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunException;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunStateCodecPort;
import fun.fengwk.kkstudio.canvas.infra.postgresql.PostgresCanvasInfraTestSupport;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 真实 PostgreSQL 上验证 Runtime 的锁、CAS、pin、资源交换与事务回滚不变量。 */
@Import(CanvasFunctionRuntimeFoundationTest.FoundationConfiguration.class)
class CanvasFunctionRuntimeFoundationTest extends PostgresCanvasInfraTestSupport {

  private static final String REQUEST_1 = "00000000-0000-0000-0000-000000000101";
  private static final String REQUEST_2 = "00000000-0000-0000-0000-000000000102";
  private static final CanvasTransform TRANSFORM = new CanvasTransform(0, 0, 100, 80);
  private static final CanvasFunctionModel MODEL =
      new CanvasFunctionModel(
          "test-model",
          "Test Image",
          CanvasResourceKind.IMAGE,
          new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()),
          List.of());

  @Autowired private CanvasResourceRepository resourceRepository;
  @Autowired private CanvasFunctionRunRepository runRepository;
  @Autowired private CanvasFunctionResourcePinRepository pinRepository;
  @Autowired private CanvasFunctionRunTransactions runtimeTransactions;
  @Autowired private CanvasFunctionModelRegistry catalog;
  @Autowired private CanvasFunctionRunStateCodecPort stateCodec;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private TestBlobAccess blobAccess;

  /** start 必须按 document/node 行串行并保持 requestId 幂等；只有真实状态前进才增加 document version。 */
  @Test
  void serializesStartAndKeepsRequestIdIdempotent() {
    UUID canvasId = addDocument();
    UUID nodeId = addFunctionNode(canvasId, "output", configWithoutReferences());

    CanvasFunctionRun first = runtimeTransactions.start(canvasId, nodeId, REQUEST_1).run();
    assertEquals(CanvasFunctionRunStatus.RUNNING, first.status());
    assertEquals(1L, version(canvasId));
    assertEquals(
        1,
        pinRepository.findByRun(canvasId, nodeId, first.requestId()).stream()
            .filter(pin -> pin.role() == CanvasFunctionResourcePin.Role.OUTPUT)
            .count());

    CanvasFunctionStartResult replay = runtimeTransactions.start(canvasId, nodeId, REQUEST_1);
    assertFalse(replay.created());
    assertEquals(first.requestId(), replay.run().requestId());
    assertEquals(first.status(), replay.run().status());
    assertEquals(decode(first), decode(replay.run()));
    assertEquals(1L, version(canvasId), "exact replay must not bump version");
    assertThrows(
        CanvasFunctionRunException.class,
        () -> runtimeTransactions.start(canvasId, nodeId, REQUEST_2));

    runtimeTransactions.cancel(canvasId, nodeId, REQUEST_1);
    CanvasFunctionRun replacement = runtimeTransactions.start(canvasId, nodeId, REQUEST_2).run();
    assertNotEquals(first.requestId(), replacement.requestId());
    assertEquals(3L, version(canvasId));
  }

  /** 冻结 manifest 必须来自同画布已连线 Resource 的 Blob facts；成功终态原子挂接预分配目标。 */
  @Test
  void freezesReferenceFactsAndAttachesOnlyThePreallocatedTarget() {
    UUID canvasId = addDocument();
    UUID sourceNodeId = addPlainNode(canvasId, "source");
    UUID targetNodeId = addFunctionNode(canvasId, "target", configWithReference(sourceNodeId));
    canvasStore.addLink(new CanvasLink(canvasId, sourceNodeId, targetNodeId));
    CanvasResource source = addBlobResource(canvasId, sourceNodeId, 0, UUID.randomUUID());

    CanvasFunctionRun running = runtimeTransactions.start(canvasId, targetNodeId, REQUEST_1).run();
    CanvasFunctionFrozenRun frozen = decode(running);
    assertEquals(source.blobId(), frozen.manifest().get(0).blobId());
    assertEquals(3L, frozen.manifest().get(0).sizeBytes());

    CanvasResource target = addBlobResource(canvasId, null, null, frozen.targetResourceId());
    assertTrue(runtimeTransactions.completeSuccess(frozen, List.of(frozen.targetResourceId())));

    CanvasResource attached = resourceRepository.findById(canvasId, target.id()).orElseThrow();
    assertEquals(targetNodeId, attached.ownerNodeId());
    assertEquals(0, attached.resourceIndex());
    assertEquals(
        CanvasFunctionRunStatus.SUCCEEDED,
        runRepository.findByNodeId(targetNodeId).orElseThrow().status());
    assertEquals(2L, version(canvasId), "start and terminal swap each bump once");
  }

  /** 外层事务回滚必须同时撤销 checkpoint state 与 document version，不能留下部分提交。 */
  @Test
  void checkpointRollbackLeavesRunAndVersionUntouched() {
    UUID canvasId = addDocument();
    UUID nodeId = addFunctionNode(canvasId, "output", configWithoutReferences());
    runtimeTransactions.start(canvasId, nodeId, REQUEST_1);
    long versionAfterStart = version(canvasId);

    TransactionTemplate template = new TransactionTemplate(transactionManager);
    assertThrows(
        IllegalStateException.class,
        () ->
            template.executeWithoutResult(
                status -> {
                  runtimeTransactions.checkpoint(
                      canvasId, nodeId, REQUEST_1, "SUBMITTED", Map.of("jobId", "job"));
                  throw new IllegalStateException("rollback");
                }));

    assertEquals(versionAfterStart, version(canvasId));
    CanvasFunctionRun current = runRepository.findByNodeId(nodeId).orElseThrow();
    assertEquals("QUEUED", current.stage());
    assertFalse(current.stateJson().contains("jobId"));
  }

  private UUID addFunctionNode(UUID canvasId, String name, String config) {
    UUID nodeId = UUID.randomUUID();
    canvasStore.addNode(
        new NodeRecord(nodeId, canvasId, name, TRANSFORM, null, MODEL.key(), config));
    return nodeId;
  }

  private UUID addPlainNode(UUID canvasId, String name) {
    UUID nodeId = UUID.randomUUID();
    canvasStore.addNode(new NodeRecord(nodeId, canvasId, name, TRANSFORM, null, null, null));
    return nodeId;
  }

  private CanvasResource addBlobResource(
      UUID canvasId, UUID ownerNodeId, Integer resourceIndex, UUID resourceId) {
    UUID blobId = UUID.randomUUID();
    blobAccess.put(new CanvasFunctionBlobAccess.BlobFacts(blobId, "image/png", 3L, 1L, 1L, null));
    jdbc.update(
        "insert into storage_blob "
            + "(id, sha256, size_bytes, media_type, width, height, ref_count, state) "
            + "values (?, ?, ?, ?, ?, ?, ?, ?)",
        blobId,
        String.format("%064x", blobId.getLeastSignificantBits() & Long.MAX_VALUE),
        3L,
        "image/png",
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
            "resource.png",
            null,
            Instant.now());
    resourceRepository.add(resource);
    return resource;
  }

  private CanvasFunctionFrozenRun decode(CanvasFunctionRun run) {
    return stateCodec.decode(
        run.stateJson(), catalog.require(stateCodec.modelKey(run.stateJson())).model());
  }

  private long version(UUID canvasId) {
    return canvasStore.findDocument(canvasId).orElseThrow().version();
  }

  private static String configWithoutReferences() {
    return "{\"prompt\":{\"segments\":[{\"type\":\"TEXT\",\"text\":\"prompt\"}]},"
        + "\"parameters\":{}}";
  }

  private static String configWithReference(UUID sourceNodeId) {
    return "{\"prompt\":{\"segments\":[{\"type\":\"TEXT\",\"text\":\"use reference\"},"
        + "{\"type\":\"REFERENCE\",\"nodeId\":\""
        + sourceNodeId
        + "\",\"index\":0}]},\"parameters\":{}}";
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FoundationConfiguration {

    @Bean
    Clock canvasFunctionClock() {
      return Clock.systemUTC();
    }

    @Bean
    CanvasFunctionAdapter foundationAdapter() {
      return new CanvasFunctionAdapter() {
        @Override
        public List<CanvasFunctionModel> models() {
          return List.of(MODEL);
        }

        @Override
        public boolean enabled() {
          return true;
        }

        @Override
        public String unavailableReason() {
          return null;
        }

        @Override
        public void preflight(CanvasFunctionFrozenRun run) {}

        @Override
        public List<UUID> execute(
            CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
          throw new UnsupportedOperationException("foundation test does not execute adapters");
        }
      };
    }

    @Bean
    TestBlobAccess testBlobAccess() {
      return new TestBlobAccess();
    }

    @Bean
    CanvasResourceLifecycle testResourceLifecycle(
        CanvasResourceRepository resources, CanvasFunctionResourcePinRepository pins) {
      return new TestResourceLifecycle(resources, pins);
    }
  }

  /** 测试只模拟 Core facts；original I/O 不参与事务 foundation。 */
  static final class TestBlobAccess implements CanvasFunctionBlobAccess {

    private final Map<UUID, BlobFacts> facts = new ConcurrentHashMap<>();

    void put(BlobFacts value) {
      facts.put(value.blobId(), value);
    }

    @Override
    public Optional<BlobFacts> findFacts(UUID blobId) {
      return Optional.ofNullable(facts.get(blobId));
    }

    @Override
    public CanvasFunctionResourceStream openOriginal(UUID blobId, long expectedSizeBytes) {
      throw new UnsupportedOperationException("foundation test does not open originals");
    }

    @Override
    public String originalUrl(UUID blobId, long expiresSeconds) {
      throw new UnsupportedOperationException("foundation test does not presign originals");
    }
  }

  /** 忠实模拟 owner/pin 行约束但不引入 Platform Blob ref_count，使 Runtime foundation 保持模块内闭环。 */
  private static final class TestResourceLifecycle implements CanvasResourceLifecycle {

    private final CanvasResourceRepository resources;
    private final CanvasFunctionResourcePinRepository pins;

    private TestResourceLifecycle(
        CanvasResourceRepository resources, CanvasFunctionResourcePinRepository pins) {
      this.resources = resources;
      this.pins = pins;
    }

    @Override
    public void releaseRunPins(UUID canvasId, UUID nodeId, UUID requestId) {
      List<CanvasFunctionResourcePin> released = pins.findByRun(canvasId, nodeId, requestId);
      pins.deleteByRun(canvasId, nodeId, requestId);
      collectUnowned(canvasId, released);
    }

    @Override
    public void releaseNodePins(UUID canvasId, UUID nodeId) {
      List<CanvasFunctionResourcePin> released = pins.findByNode(canvasId, nodeId);
      pins.deleteByNode(canvasId, nodeId);
      collectUnowned(canvasId, released);
    }

    @Override
    public void releaseCanvasPins(UUID canvasId) {
      pins.deleteByCanvas(canvasId);
    }

    @Override
    public void deleteOwnedResources(UUID canvasId, UUID nodeId) {
      for (CanvasResource resource : resources.findByOwnerNode(canvasId, nodeId)) {
        if (pins.countByResource(canvasId, resource.id()) > 0) {
          resources.detachOwner(canvasId, resource.id(), nodeId);
        } else {
          resources.delete(canvasId, resource.id());
        }
      }
    }

    @Override
    public CanvasResource replaceOwnedWithTarget(
        UUID canvasId, UUID nodeId, UUID targetResourceId) {
      CanvasResource target = resources.findByIdForUpdate(canvasId, targetResourceId).orElseThrow();
      for (CanvasResource current : resources.findByOwnerNode(canvasId, nodeId)) {
        if (pins.countByResource(canvasId, current.id()) > 0) {
          resources.detachOwner(canvasId, current.id(), nodeId);
        } else {
          resources.delete(canvasId, current.id());
        }
      }
      if (!resources.attachOwner(canvasId, targetResourceId, nodeId, 0)) {
        throw new IllegalStateException("attach test target failed");
      }
      return new CanvasResource(
          target.id(),
          target.canvasId(),
          nodeId,
          0,
          target.blobId(),
          target.name(),
          target.textContent(),
          target.createdAt());
    }

    @Override
    public void discardUnownedTarget(UUID canvasId, UUID targetResourceId) {
      resources
          .findByIdForUpdate(canvasId, targetResourceId)
          .filter(resource -> resource.ownerNodeId() == null)
          .ifPresent(resource -> resources.delete(canvasId, resource.id()));
    }

    @Override
    public void deleteCanvasResources(UUID canvasId) {
      for (CanvasResource resource : resources.findByCanvasId(canvasId)) {
        resources.delete(canvasId, resource.id());
      }
    }

    private void collectUnowned(UUID canvasId, List<CanvasFunctionResourcePin> released) {
      Set<UUID> resourceIds = new LinkedHashSet<>();
      for (CanvasFunctionResourcePin pin : released) {
        resourceIds.add(pin.resourceId());
      }
      for (UUID resourceId : resourceIds) {
        if (pins.countByResource(canvasId, resourceId) == 0) {
          resources
              .findByIdForUpdate(canvasId, resourceId)
              .filter(resource -> resource.ownerNodeId() == null)
              .ifPresent(resource -> resources.delete(canvasId, resource.id()));
        }
      }
    }
  }
}
