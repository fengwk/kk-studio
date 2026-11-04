package fun.fengwk.kkstudio.canvas.infra.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.postgresql.PGNotification;
import org.springframework.context.annotation.Import;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunException;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasFunctionVersion;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBinding;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasJson;
import fun.fengwk.kkstudio.canvas.function.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.infra.platform.PluginCanvasFunctionCatalogSource;
import fun.fengwk.kkstudio.canvas.notification.CanvasVersionNotifications;
import fun.fengwk.kkstudio.canvas.testing.TestCanvasFunctionCatalogSource;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 启动不变量：目录版本围栏、requestId 幂等、目录租用与事务边界、args 默认值与 revision 通知。 */
@Import(CanvasFunctionRuntimeTestSupport.FoundationConfiguration.class)
class CanvasFunctionStartTest extends CanvasFunctionRuntimeTestSupport {

  /** start 必须按 document/node 行串行并保持 requestId 幂等；只有真实状态前进才增加 document version。 */
  @Test
  void serializesStartAndKeepsRequestIdIdempotent() {
    UUID canvasId = addDocument();
    UUID nodeId = addFunctionNode(canvasId, "output", configWithoutReferences());

    CanvasFunctionRun first = startRun(canvasId, nodeId, REQUEST_1).run();
    assertEquals(CanvasFunctionRunStatus.READY, first.status());
    assertEquals(1L, version(canvasId));
    // 无引用的 Run 在启动阶段不产生 pin：预分配目标没有 Resource 行，OUTPUT pin 属于物化事务。
    assertTrue(pinRepository.findByRun(canvasId, nodeId, first.requestId()).isEmpty());

    CanvasFunctionStartResult replay = startRun(canvasId, nodeId, REQUEST_1);
    assertFalse(replay.created());
    assertEquals(first.requestId(), replay.run().requestId());
    assertEquals(first.status(), replay.run().status());
    assertEquals(decode(first), decode(replay.run()));
    assertEquals(1L, version(canvasId), "exact replay must not bump version");
    assertThrows(CanvasFunctionRunException.class, () -> startRun(canvasId, nodeId, REQUEST_2));

    CanvasFunctionFrozenRun firstFrozen = decode(first);
    CanvasResource cancelledTarget =
        materializeTarget(canvasId, nodeId, first.requestId(), firstFrozen.output(0));
    runtimeTransactions.cancel(canvasId, nodeId, REQUEST_1);
    CanvasFunctionRun cancelledReplay = runtimeTransactions.cancel(canvasId, nodeId, REQUEST_1);
    assertEquals(CanvasFunctionRunStatus.CANCELLED, cancelledReplay.status());
    assertTrue(pinRepository.findByRun(canvasId, nodeId, first.requestId()).isEmpty());
    assertTrue(resourceRepository.findById(canvasId, cancelledTarget.id()).isEmpty());
    assertEquals(2L, version(canvasId), "terminal cancel replay must not bump version");
    CanvasFunctionRun replacement = startRun(canvasId, nodeId, REQUEST_2).run();
    assertNotEquals(first.requestId(), replacement.requestId());
    assertEquals(3L, version(canvasId));
  }

  /** start 的目录租用保持到真实事务提交后才释放。 */
  @Test
  void startHoldsCurrentLeaseUntilTransactionCompletion() {
    UUID canvasId = addDocument();
    UUID nodeId = addFunctionNode(canvasId, "output", configWithoutReferences());

    CanvasFunctionStartResult started =
        transactions.execute(
            status -> {
              CanvasFunctionStartResult result =
                  runtimeTransactions.start(
                      canvasId, nodeId, REQUEST_1, currentVersion(DEFINITION.name()));
              // 事务内租用仍然打开：释放必须等到 afterCompletion，而不是方法返回前。
              assertEquals(1, catalogSource.openLeases());
              return result;
            });

    assertEquals(CanvasFunctionRunStatus.READY, started.run().status());
    assertEquals(1L, version(canvasId));
    assertEquals(0, catalogSource.openLeases());
  }

  /** 回滚同样释放租用，且不留下 Run 行与 version 推进。 */
  @Test
  void startReleasesCurrentLeaseOnRollback() {
    UUID canvasId = addDocument();
    UUID nodeId = addFunctionNode(canvasId, "output", configWithoutReferences());

    transactions.execute(
        status -> {
          runtimeTransactions.start(canvasId, nodeId, REQUEST_1, currentVersion(DEFINITION.name()));
          assertEquals(1, catalogSource.openLeases());
          status.setRollbackOnly();
          return null;
        });

    assertEquals(0, catalogSource.openLeases());
    assertTrue(runRepository.findByNodeId(nodeId).isEmpty());
    assertEquals(0L, version(canvasId));
  }

  /** 没有真实事务同步时必须先释放租用再明确拒绝，绝不降级为「方法内加锁」继续执行。 */
  @Test
  void startWithoutActiveTransactionReleasesLeaseAndRejects() {
    UUID canvasId = addDocument();
    UUID nodeId = addFunctionNode(canvasId, "output", configWithoutReferences());

    assertThrows(
        IllegalStateException.class,
        () ->
            runtimeTransactions.start(
                canvasId, nodeId, REQUEST_1, currentVersion(DEFINITION.name())));

    assertEquals(0, catalogSource.openLeases());
    assertTrue(runRepository.findByNodeId(nodeId).isEmpty());
    assertEquals(0L, version(canvasId));
  }

  /** 当前目录漂移不改写已持久化的完整定义；旧绑定在新目录中被拒绝，旧版本也不能再启动。 */
  @Test
  void frozenBindingKeepsDefinitionAfterCurrentDrift() {
    UUID canvasId = addDocument();
    UUID nodeId = addFunctionNode(canvasId, "output", configWithoutReferences());

    TestCanvasFunctionCatalogSource source = new TestCanvasFunctionCatalogSource(frozenCatalog());
    CanvasFunctionRunTransactions driftTransactions = transactionsFor(source.source());
    CanvasFunctionRun run =
        transactions
            .execute(
                status ->
                    driftTransactions.start(canvasId, nodeId, REQUEST_1, versionOf(DEFINITION)))
            .run();
    CanvasFunctionBinding frozen = decode(run).binding();
    assertEquals(DEFINITION, frozen.definition());

    CanvasFunctionDefinition drifted =
        CanvasFunctionDefinition.of(
            DEFINITION.name(),
            "Drifted Definition",
            ARGS_SCHEMA,
            CanvasResourceKind.VIDEO,
            new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()));
    source.replace(catalogOf(drifted));

    // 持久 state 冻结的完整 binding 不随 current 漂移。
    assertEquals(frozen, decode(run).binding());
    try (PluginCanvasFunctionCatalogSource.Lease current = source.source().acquire()) {
      assertEquals(drifted, current.bind(DEFINITION.name()).definition());
      // 同名函数定义已变：旧绑定被拒绝，不退回当前版本。
      assertThrows(IllegalStateException.class, () -> current.require(frozen));
    }
    // 目录变化后，用旧版本对另一节点 start 必须 409：既不自动重读最新目录，也不自动 retry。
    UUID otherNode = addFunctionNode(canvasId, "other", configWithoutReferences());
    long versionBeforeReject = version(canvasId);
    CanvasFunctionRunException rejected =
        assertThrows(
            CanvasFunctionRunException.class,
            () ->
                transactions.execute(
                    status ->
                        driftTransactions.start(
                            canvasId, otherNode, REQUEST_2, versionOf(DEFINITION))));
    assertEquals(CanvasFunctionRunException.Reason.CONFLICT, rejected.reason());
    assertTrue(runRepository.findByNodeId(otherNode).isEmpty());
    assertEquals(versionBeforeReject, version(canvasId));
    assertEquals(0, source.openLeases());
    assertEquals(0, catalogSource.openLeases());
  }

  /**
   * 验收证据：定义 fingerprint 不匹配（同名函数换了定义）时 409，且不新增 Run/pin、不推进版本、不触发 adapter 外部调用。
   *
   * <p>围栏必须在 pin、Run 写入与 preflight 之前生效，否则一次过期请求就会留下执行事实或付费副作用。
   */
  @Test
  void startRejectsStaleFingerprintBeforeAnySideEffect() {
    UUID canvasId = addDocument();
    UUID nodeId = addFunctionNode(canvasId, "output", configWithoutReferences());
    TestCanvasFunctionAdapter adapter = new TestCanvasFunctionAdapter(DEFINITION);
    TestCanvasFunctionCatalogSource source =
        new TestCanvasFunctionCatalogSource(catalogOf(adapter));
    CanvasFunctionRunTransactions candidate = transactionsFor(source.source());

    CanvasFunctionRunException conflict =
        assertThrows(
            CanvasFunctionRunException.class,
            () ->
                transactions.execute(
                    status ->
                        candidate.start(
                            canvasId, nodeId, REQUEST_1, versionOf(MULTI_MEDIA_DEFINITION))));

    assertEquals(CanvasFunctionRunException.Reason.CONFLICT, conflict.reason());
    assertEquals(0, adapter.preflightCalls, "stale fingerprint must not reach the adapter");
    assertTrue(runRepository.findByNodeId(nodeId).isEmpty());
    assertTrue(pinRepository.findByNode(canvasId, nodeId).isEmpty());
    assertTrue(resourceRepository.findByOwnerNode(canvasId, nodeId).isEmpty());
    assertEquals(0L, version(canvasId));
    assertEquals(0, source.openLeases());
  }

  /**
   * 验收证据：目录已移除该函数时，旧客户端版本得到 409 版本冲突，而不是「未知函数」400。
   *
   * <p>「函数被移除」不能被误报为请求非法，否则客户端会去改请求而不是刷新目录。
   */
  @Test
  void startRejectsWhenTheFunctionWasRemoved() {
    for (CanvasFunctionCatalog current :
        List.of(CanvasFunctionCatalog.from(List.of()), catalogOf(MULTI_MEDIA_DEFINITION))) {
      UUID canvasId = addDocument();
      UUID nodeId = addFunctionNode(canvasId, "output", configWithoutReferences());
      TestCanvasFunctionCatalogSource source = new TestCanvasFunctionCatalogSource(current);
      CanvasFunctionRunTransactions candidate = transactionsFor(source.source());

      CanvasFunctionRunException conflict =
          assertThrows(
              CanvasFunctionRunException.class,
              () ->
                  transactions.execute(
                      status ->
                          candidate.start(canvasId, nodeId, REQUEST_1, versionOf(DEFINITION))));

      assertEquals(CanvasFunctionRunException.Reason.CONFLICT, conflict.reason());
      assertEquals(
          "Canvas Function catalog changed; refresh the directory before starting this function",
          conflict.getMessage());
      assertTrue(runRepository.findByNodeId(nodeId).isEmpty());
      assertTrue(pinRepository.findByNode(canvasId, nodeId).isEmpty());
      assertTrue(resourceRepository.findByOwnerNode(canvasId, nodeId).isEmpty());
      assertEquals(0L, version(canvasId));
      assertEquals(1, source.acquireCalls(), "the fence must not acquire a second lease");
      assertEquals(0, source.openLeases());
    }
  }

  /**
   * 验收证据：目录变化后，精确 requestId 重放仍命中已接受结果，不重新取目录、pin 或提交。
   *
   * <p>重放优先于版本校验与按名绑定，因此即使函数已被移除，已接受的执行事实也原样返回、不会被二次冻结。
   */
  @Test
  void exactRequestIdReplaySurvivesCatalogChange() {
    for (CanvasFunctionCatalog changed :
        List.of(CanvasFunctionCatalog.from(List.of()), catalogOf(MULTI_MEDIA_DEFINITION))) {
      UUID canvasId = addDocument();
      UUID nodeId = addFunctionNode(canvasId, "output", configWithoutReferences());
      TestCanvasFunctionAdapter accepted = new TestCanvasFunctionAdapter(DEFINITION);
      TestCanvasFunctionCatalogSource source =
          new TestCanvasFunctionCatalogSource(catalogOf(accepted));
      CanvasFunctionRunTransactions candidate = transactionsFor(source.source());
      CanvasFunctionVersion acceptedVersion = versionOf(DEFINITION);

      CanvasFunctionRun first =
          transactions
              .execute(status -> candidate.start(canvasId, nodeId, REQUEST_1, acceptedVersion))
              .run();
      long versionAfterStart = version(canvasId);
      int acquiresAfterStart = source.acquireCalls();
      assertEquals(1, accepted.preflightCalls);

      source.replace(changed);

      CanvasFunctionStartResult replay =
          transactions.execute(
              status -> candidate.start(canvasId, nodeId, REQUEST_1, acceptedVersion));

      assertFalse(replay.created());
      assertEquals(first.requestId(), replay.run().requestId());
      assertEquals(first.status(), replay.run().status());
      assertEquals(decode(first), decode(replay.run()));
      assertEquals(versionAfterStart, version(canvasId), "exact replay must not bump version");
      assertEquals(1, accepted.preflightCalls, "exact replay must not repeat adapter side effects");
      assertEquals(acquiresAfterStart, source.acquireCalls(), "exact replay must not take a lease");
      assertTrue(pinRepository.findByNode(canvasId, nodeId).isEmpty(), "exact replay must not pin");
      assertEquals(0, source.openLeases());
    }
  }

  /** 相同代码重新构建目录后，持久绑定仍能验证通过。 */
  @Test
  void validatesPersistedBindingAgainstARebuiltSameCodeCatalog() {
    UUID canvasId = addDocument();
    UUID nodeId = addFunctionNode(canvasId, "output", configWithoutReferences());
    CanvasFunctionRun run = startRun(canvasId, nodeId, REQUEST_1).run();
    CanvasFunctionBinding frozen = decode(run).binding();

    TestCanvasFunctionCatalogSource restarted =
        new TestCanvasFunctionCatalogSource(catalogOf(DEFINITION));

    try (PluginCanvasFunctionCatalogSource.Lease lease = restarted.source().acquire()) {
      assertEquals(DEFINITION, lease.require(frozen).function());
    }
    assertEquals(0, catalogSource.openLeases());
    assertEquals(0, restarted.openLeases());
  }

  /** 非 Function 节点不得启动：先拒绝再取租用，不冻结、不写 Run、不推进版本。 */
  @Test
  void startRejectsPlainNodeWithoutLeaseOrSideEffects() {
    UUID canvasId = addDocument();
    UUID nodeId = addPlainNode(canvasId, "text");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            transactions.execute(
                status ->
                    runtimeTransactions.start(
                        canvasId, nodeId, REQUEST_1, currentVersion(DEFINITION.name()))));

    assertEquals(0, catalogSource.openLeases());
    assertTrue(runRepository.findByNodeId(nodeId).isEmpty());
    assertEquals(0L, version(canvasId));
  }

  /** start 经 bumpVersion 前进 revision，必须在提交时发布 canvas_revision 失效提示 */
  @Test
  void startNotifiesRevisionThroughBumpVersion() throws Exception {
    UUID canvasId = addDocument();
    UUID nodeId = addFunctionNode(canvasId, "output", configWithoutReferences());
    try (Connection listener = listenOn(CanvasVersionNotifications.CHANNEL)) {
      startRun(canvasId, nodeId, REQUEST_1);
      PGNotification[] notifications = pollNotifications(listener, 5_000);
      assertEquals(1, notifications.length);
      assertEquals(CanvasVersionNotifications.CHANNEL, notifications[0].getName());
      assertEquals(canvasId + ":1", notifications[0].getParameter());
    }
  }

  /** 冻结 manifest 必须来自同画布已连线 Resource 的 Blob facts；成功终态原子挂接预分配目标。 */
  @Test
  void freezesReferenceFactsAndAttachesOnlyThePreallocatedTarget() {
    UUID canvasId = addDocument();
    UUID sourceNodeId = addPlainNode(canvasId, "source");
    UUID targetNodeId = addFunctionNode(canvasId, "target", configWithReference(sourceNodeId));
    CanvasResource source = addBlobResource(canvasId, sourceNodeId, 0, UUID.randomUUID());

    ClaimedRun claim = claimStartedRun(canvasId, targetNodeId, REQUEST_1, "foundation-success");
    CanvasFunctionFrozenRun frozen = decode(claim.run());
    assertEquals(source.blobId(), frozen.manifest().get(0).blobId());
    assertEquals(3L, frozen.manifest().get(0).sizeBytes());

    CanvasResource target =
        materializeTarget(canvasId, targetNodeId, frozen.requestId(), frozen.output(0));
    assertTrue(
        runtimeTransactions.completeSuccess(
            frozen, claim.leaseToken(), frozen.outputResourceIds()));

    CanvasResource attached = resourceRepository.findById(canvasId, target.id()).orElseThrow();
    assertEquals(targetNodeId, attached.ownerNodeId());
    assertEquals(0, attached.resourceIndex());
    assertEquals(
        CanvasFunctionRunStatus.SUCCEEDED,
        runRepository.findByNodeId(targetNodeId).orElseThrow().status());
    assertTrue(pinRepository.findByRun(canvasId, targetNodeId, frozen.requestId()).isEmpty());
    assertEquals(2L, version(canvasId), "start and terminal swap each bump once");
  }

  /** 验收证据 5：args Schema 校验在 start 阶段拦截未知字段、类型错误与缺失必填，合法输入补齐默认值并冻结到计划中。 */
  @Test
  void argsValidationRejectsMalformedAndFillsDefaultsAtStart() {
    UUID canvasId = addDocument();

    // 未知字段 extra
    UUID nodeUnknown = addFunctionNode(canvasId, "node1", "{\"prompt\":\"test\",\"extra\":1}");
    assertThrows(IllegalArgumentException.class, () -> startRun(canvasId, nodeUnknown, REQUEST_1));

    // 缺少必填字段 prompt
    UUID nodeMissing = addFunctionNode(canvasId, "node2", "{\"ratio\":\"16:9\"}");
    assertThrows(IllegalArgumentException.class, () -> startRun(canvasId, nodeMissing, REQUEST_1));

    // 类型错误：prompt 应为 string
    UUID nodeWrongType = addFunctionNode(canvasId, "node3", "{\"prompt\":123}");
    assertThrows(
        IllegalArgumentException.class, () -> startRun(canvasId, nodeWrongType, REQUEST_1));

    // 合法输入：只传必填 prompt，默认值 ratio="16:9" 必须被自动补齐并冻结
    UUID nodeValid = addFunctionNode(canvasId, "node4", "{\"prompt\":\"hello world\"}");
    CanvasFunctionRun run = startRun(canvasId, nodeValid, REQUEST_1).run();
    CanvasFunctionFrozenRun frozen = decode(run);
    assertEquals(new CanvasJson.JsonText("hello world"), frozen.args().values().get("prompt"));
    assertEquals(new CanvasJson.JsonText("16:9"), frozen.args().values().get("ratio"));
  }
}
