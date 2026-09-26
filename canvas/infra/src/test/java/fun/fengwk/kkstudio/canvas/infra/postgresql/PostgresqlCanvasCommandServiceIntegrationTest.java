package fun.fengwk.kkstudio.canvas.infra.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.canvas.CanvasCommand;
import fun.fengwk.kkstudio.canvas.CanvasCommandResult;
import fun.fengwk.kkstudio.canvas.CanvasCommandResult.Accepted;
import fun.fengwk.kkstudio.canvas.CanvasCommandResult.Conflicted;
import fun.fengwk.kkstudio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.canvas.CanvasConflict;
import fun.fengwk.kkstudio.canvas.CanvasConflictException;
import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasFunction;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasGroupPatch;
import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasNodePatch;
import fun.fengwk.kkstudio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceInput;
import fun.fengwk.kkstudio.canvas.CanvasResourceNode;
import fun.fengwk.kkstudio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.canvas.CanvasValidationException;

import java.util.List;
import java.util.UUID;

/**
 * typed command 的 PostgreSQL 接受语义：原子性、幂等、语义组前置条件、资源生命周期与画布深删除。
 *
 * <p>这是本切片的验收证据：断言都落在真实数据库行上，因此“拒绝不留部分写入”“接受恰好前进一次 revision”“重复请求只返回当时的接受位置”等 事实不由 mock
 * 声称，而由提交后的行状态证明。
 */
class PostgresqlCanvasCommandServiceIntegrationTest extends PostgresCanvasInfraTestSupport {

  private static final CanvasTransform TRANSFORM = new CanvasTransform(0, 0, 10, 10);

  @Autowired private CanvasCommandService commandService;
  @Autowired private CanvasQueryService queryService;
  @Autowired private CanvasFunctionRunRepository runRepository;

  /** 画布创建只接受可显示标题，revision 从 0 开始。 */
  @Test
  void createCanvasValidatesAndNormalizesTitle() {
    CanvasDocument document = commandService.createCanvas("  canvas  ");
    assertEquals("canvas", document.title());
    assertEquals(0L, document.revision());
    assertEquals(document, canvasStore.findDocument(document.id()).orElseThrow());

    assertThrows(CanvasValidationException.class, () -> commandService.createCanvas(null));
    assertThrows(CanvasValidationException.class, () -> commandService.createCanvas("   "));
    assertThrows(CanvasValidationException.class, () -> commandService.createCanvas("bad\nname"));
    assertThrows(
        CanvasValidationException.class, () -> commandService.createCanvas("x".repeat(257)));
  }

  /** 多命令批要么整体提交、要么整体不提交：接受后所有行与 revision 同时可见，patch 携带完整变化集。 */
  @Test
  void acceptedBatchCommitsEveryRowAndAdvancesRevisionOnce() {
    UUID canvasId = commandService.createCanvas("canvas").id();
    UUID nodeId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();

    CanvasCommandResult result =
        apply(
            canvasId,
            List.of(
                new CanvasCommand.CreateGroup(groupId, "group", TRANSFORM),
                new CanvasCommand.CreateNode(
                    nodeId,
                    "node",
                    TRANSFORM,
                    List.of(new CanvasResourceInput.Text("text", "body"))),
                new CanvasCommand.SetNodeGroup(nodeId, null, groupId)));

    Accepted accepted = assertInstanceOf(Accepted.class, result);
    assertEquals(1L, accepted.patch().revision());
    CanvasResourceNode patchedNode =
        assertInstanceOf(CanvasNodePatch.Upsert.class, accepted.patch().nodes().get(0)).node();
    assertEquals(nodeId, patchedNode.id());
    assertEquals(groupId, patchedNode.groupId());
    assertEquals("body", patchedNode.resources().get(0).textContent());
    assertInstanceOf(CanvasGroupPatch.Upsert.class, accepted.patch().groups().get(0));

    CanvasSnapshot snapshot = findSnapshot(canvasId);
    assertEquals(1L, snapshot.document().revision());
    assertEquals(List.of(nodeId), snapshot.nodes().stream().map(CanvasResourceNode::id).toList());
    assertEquals(groupId, snapshot.nodes().get(0).groupId());
    assertEquals(1, snapshot.nodes().get(0).resources().size());
    assertEquals(1L, recordedAcceptedRevision(canvasId));
  }

  /** 不同节点、同节点不同语义组互不覆盖：两个基于同一编辑基线的批都成功；同语义组的过期批返回服务端权威值且不写入任何行。 */
  @Test
  void perGroupBaselinesRejectOnlyTheStaleGroup() {
    UUID canvasId = commandService.createCanvas("canvas").id();
    NodeFixture first = addTextNode(canvasId, "first");
    NodeFixture second = addTextNode(canvasId, "second");

    assertInstanceOf(
        Accepted.class,
        apply(
            canvasId,
            List.of(
                new CanvasCommand.RenameNode(second.nodeId, second.name, "renamed"),
                new CanvasCommand.UpdateNodeTransform(
                    first.nodeId, new CanvasTransform(5, 6, 20, 20), first.transform))));
    assertInstanceOf(
        Accepted.class,
        apply(
            canvasId,
            List.of(new CanvasCommand.RenameNode(first.nodeId, first.name, "renamed-first"))));

    Conflicted stale =
        assertInstanceOf(
            Conflicted.class,
            apply(
                canvasId,
                List.of(new CanvasCommand.RenameNode(second.nodeId, second.name, "again"))));

    CanvasConflict.StaleNode conflict =
        assertInstanceOf(CanvasConflict.StaleNode.class, stale.conflicts().get(0));
    assertEquals(CanvasConflict.NodeGroup.NAME, conflict.group());
    assertEquals("renamed", conflict.current().name());
    assertEquals(2L, findSnapshot(canvasId).document().revision());
    assertEquals(
        "renamed",
        findSnapshot(canvasId).nodes().stream()
            .filter(node -> node.id().equals(second.nodeId))
            .findFirst()
            .orElseThrow()
            .name());
  }

  /** 无变化的批不推进 revision，但仍记录接受位置；相同请求重放只返回当时的接受位置。 */
  @Test
  void noOpBatchAndExactReplayReturnRecordedRevision() {
    UUID canvasId = commandService.createCanvas("canvas").id();
    NodeFixture node = addTextNode(canvasId, "body");
    List<CanvasCommand> commands =
        List.of(
            new CanvasCommand.RenameNode(node.nodeId, node.name, node.name),
            new CanvasCommand.SetNodeResources(
                node.nodeId,
                List.of(node.resourceId),
                List.of(new CanvasResourceInput.Keep(node.resourceId))));

    Accepted accepted = assertInstanceOf(Accepted.class, apply(canvasId, commands));
    assertEquals(0L, accepted.patch().revision());
    assertTrue(accepted.patch().isEmpty());
    assertEquals(0L, recordedAcceptedRevision(canvasId));

    UUID idempotencyKey = UUID.randomUUID();
    Accepted first =
        assertInstanceOf(
            Accepted.class, commandService.applyCommands(canvasId, idempotencyKey, commands));
    Accepted replay =
        assertInstanceOf(
            Accepted.class, commandService.applyCommands(canvasId, idempotencyKey, commands));
    assertEquals(first.patch(), replay.patch());
    assertEquals(0L, replay.patch().revision());
    assertTrue(replay.patch().isEmpty());
  }

  /** 相同幂等键绑定不同请求指纹时必须拒绝，且不写入任何行。 */
  @Test
  void idempotencyKeyBoundToAnotherRequestIsRejected() {
    UUID canvasId = commandService.createCanvas("canvas").id();
    NodeFixture node = addTextNode(canvasId, "body");
    UUID idempotencyKey = UUID.randomUUID();
    apply(
        canvasId,
        idempotencyKey,
        List.of(new CanvasCommand.RenameNode(node.nodeId, node.name, "a")));

    CanvasConflictException conflict =
        assertThrows(
            CanvasConflictException.class,
            () ->
                apply(
                    canvasId,
                    idempotencyKey,
                    List.of(new CanvasCommand.RenameNode(node.nodeId, node.name, "b"))));

    assertEquals(CanvasConflictException.Reason.IDEMPOTENCY_CONFLICT, conflict.reason());
    assertEquals("a", findSnapshot(canvasId).nodes().get(0).name());
    assertEquals(1L, findSnapshot(canvasId).document().revision());
  }

  /** 缺失画布的写入准入直接失败，不隐式创建聚合。 */
  @Test
  void missingCanvasIsRejected() {
    UUID canvasId = UUID.randomUUID();
    CanvasConflictException conflict =
        assertThrows(
            CanvasConflictException.class,
            () ->
                apply(
                    canvasId,
                    List.of(
                        new CanvasCommand.CreateNode(
                            UUID.randomUUID(),
                            "node",
                            TRANSFORM,
                            List.of(new CanvasResourceInput.Text("text", "body"))))));

    assertEquals(CanvasConflictException.Reason.CANVAS_NOT_FOUND, conflict.reason());
    assertTrue(canvasStore.findDocument(canvasId).isEmpty());
  }

  /** 文本编辑创建新的不可变内容行：旧行删除、新行占据原槽位，之前的资源 id 不再可见。 */
  @Test
  void textEditReplacesResourceRow() {
    UUID canvasId = commandService.createCanvas("canvas").id();
    NodeFixture node = addTextNode(canvasId, "before");

    Accepted accepted =
        assertInstanceOf(
            Accepted.class,
            apply(
                canvasId,
                List.of(
                    new CanvasCommand.SetNodeResources(
                        node.nodeId,
                        List.of(node.resourceId),
                        List.of(new CanvasResourceInput.Text("text", "after"))))));

    CanvasResourceNode patched =
        assertInstanceOf(CanvasNodePatch.Upsert.class, accepted.patch().nodes().get(0)).node();
    CanvasResource current = patched.resources().get(0);
    assertEquals("after", current.textContent());
    assertEquals(0, current.resourceIndex());
    assertNotEquals(node.resourceId, current.id());
    assertTrue(resourceRepository.findById(canvasId, node.resourceId).isEmpty());
    assertTrue(resourceRepository.findById(canvasId, current.id()).isPresent());
  }

  /** 媒体资源被替换时行与 Blob 引用一起回收：行删除与 ref_count 递减同事务。 */
  @Test
  void replacingBlobResourceReleasesBlobReference() {
    UUID canvasId = commandService.createCanvas("canvas").id();
    UUID blobId = addBlob();
    UUID nodeId = UUID.randomUUID();
    Accepted created =
        assertInstanceOf(
            Accepted.class,
            apply(
                canvasId,
                List.of(
                    new CanvasCommand.CreateNode(
                        nodeId,
                        "media",
                        TRANSFORM,
                        List.of(new CanvasResourceInput.Blob("image.png", blobId))))));
    UUID resourceId =
        assertInstanceOf(CanvasNodePatch.Upsert.class, created.patch().nodes().get(0))
            .node()
            .resources()
            .get(0)
            .id();
    assertEquals(1, blobRefCount(blobId));

    assertInstanceOf(
        Accepted.class,
        apply(
            canvasId,
            List.of(
                new CanvasCommand.SetNodeResources(
                    nodeId,
                    List.of(resourceId),
                    List.of(new CanvasResourceInput.Blob("image.png", addBlob()))))));

    assertTrue(resourceRepository.findById(canvasId, resourceId).isEmpty());
    // Storage releaseOnce 语义：减到 0 的同一语句内切换为 DELETING。
    assertEquals(0, blobRefCount(blobId));
    assertEquals(
        "DELETING",
        jdbc.queryForObject("select state from storage_blob where id = ?", String.class, blobId));
  }

  /** 仍被 Run pin 的历史资源只解除挂接：行与内容保留，供冻结输入继续读取。 */
  @Test
  void pinnedResourceIsDetachedInsteadOfDeleted() {
    UUID canvasId = commandService.createCanvas("canvas").id();
    NodeFixture node = addTextNode(canvasId, "pinned");
    canvasStore.updateNode(node.record(function()));
    UUID requestId = addPinnedRun(canvasId, node.nodeId, node.resourceId);

    assertInstanceOf(
        Accepted.class,
        apply(
            canvasId,
            List.of(
                new CanvasCommand.SetNodeResources(
                    node.nodeId,
                    List.of(node.resourceId),
                    List.of(new CanvasResourceInput.Text("text", "replacement"))))));

    CanvasResource detached = resourceRepository.findById(canvasId, node.resourceId).orElseThrow();
    assertNull(detached.ownerNodeId());
    assertEquals("pinned", detached.textContent());
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(1) from canvas_function_resource_pin where node_id = ? and request_id = ?",
            Integer.class,
            node.nodeId,
            requestId));
  }

  /** 删除被引用节点必须先显式解除引用；同一原子批内解除后可删除，并清理 Run 行与 pin。 */
  @Test
  void referencedNodeDeletionRequiresExplicitRelease() {
    UUID canvasId = commandService.createCanvas("canvas").id();
    NodeFixture source = addTextNode(canvasId, "images");
    canvasStore.updateNode(source.record(function()));
    addPinnedRun(canvasId, source.nodeId, source.resourceId);
    NodeFixture consumer = addFunctionNode(canvasId, "video.generate");
    CanvasFunction referencing =
        new CanvasFunction(
            "video.generate",
            CanvasJson.parseObject(
                "{\"prompt\":{\"segments\":[{\"type\":\"resource\",\"nodeId\":\""
                    + source.nodeId
                    + "\",\"index\":0}]}}"));
    assertInstanceOf(
        Accepted.class,
        apply(
            canvasId,
            List.of(new CanvasCommand.SetNodeFunction(consumer.nodeId, function(), referencing))));

    Conflicted rejected =
        assertInstanceOf(
            Conflicted.class,
            apply(
                canvasId,
                List.of(
                    new CanvasCommand.DeleteNode(
                        source.nodeId, List.of(source.resourceId), function()))));
    CanvasConflict.NodeReferenced conflict =
        assertInstanceOf(CanvasConflict.NodeReferenced.class, rejected.conflicts().get(0));
    assertEquals(List.of(consumer.nodeId), conflict.referencingNodeIds());
    assertNotNull(canvasStore.findNode(canvasId, source.nodeId).orElse(null));

    assertInstanceOf(
        Accepted.class,
        apply(
            canvasId,
            List.of(
                new CanvasCommand.SetNodeFunction(
                    consumer.nodeId,
                    referencing,
                    new CanvasFunction("video.generate", CanvasJson.parseObject("{}"))),
                new CanvasCommand.DeleteNode(
                    source.nodeId, List.of(source.resourceId), function()))));

    assertTrue(canvasStore.findNode(canvasId, source.nodeId).isEmpty());
    assertTrue(resourceRepository.findById(canvasId, source.resourceId).isEmpty());
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(1) from canvas_function_run where node_id = ?",
            Integer.class,
            source.nodeId));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(1) from canvas_function_resource_pin where node_id = ?",
            Integer.class,
            source.nodeId));
  }

  /** 分组命令保持成员一致，删除分组要求成员基线与当前一致。 */
  @Test
  void groupDeletionRequiresMemberBaseline() {
    UUID canvasId = commandService.createCanvas("canvas").id();
    NodeFixture node = addTextNode(canvasId, "body");
    UUID groupId = UUID.randomUUID();
    apply(
        canvasId,
        List.of(
            new CanvasCommand.CreateGroup(groupId, "group", TRANSFORM),
            new CanvasCommand.SetNodeGroup(node.nodeId, null, groupId)));

    Conflicted stale =
        assertInstanceOf(
            Conflicted.class,
            apply(canvasId, List.of(new CanvasCommand.DeleteGroup(groupId, List.of()))));
    assertInstanceOf(CanvasConflict.StaleNode.class, stale.conflicts().get(0));
    assertNotNull(canvasStore.findGroup(canvasId, groupId).orElse(null));

    assertInstanceOf(
        Accepted.class,
        apply(canvasId, List.of(new CanvasCommand.DeleteGroup(groupId, List.of(node.nodeId)))));
    assertTrue(canvasStore.findGroup(canvasId, groupId).isEmpty());
    assertNull(canvasStore.findNode(canvasId, node.nodeId).orElseThrow().groupId());
  }

  /** 画布深删除清空 pin、Run、Resource、Node、Group、dedup 与 document；存在未终结执行时拒绝删除。 */
  @Test
  void deleteCanvasClearsEveryRowAndRejectsActiveRun() {
    UUID canvasId = commandService.createCanvas("canvas").id();
    NodeFixture node = addTextNode(canvasId, "body");
    UUID groupId = UUID.randomUUID();
    apply(
        canvasId,
        List.of(
            new CanvasCommand.CreateGroup(groupId, "group", TRANSFORM),
            new CanvasCommand.SetNodeGroup(node.nodeId, null, groupId)));
    UUID requestId = addPinnedRun(canvasId, node.nodeId, node.resourceId);
    jdbc.update(
        "update canvas_function_run set status = 'READY', available_at = current_timestamp,"
            + " lease_token = null, lease_until = null where node_id = ? and request_id = ?",
        node.nodeId,
        requestId);

    assertThrows(IllegalStateException.class, () -> commandService.deleteCanvas(canvasId));
    assertNotNull(canvasStore.findDocument(canvasId).orElse(null));

    jdbc.update(
        "update canvas_function_run set status = 'CANCELLED', available_at = null where node_id = ?",
        node.nodeId);
    commandService.deleteCanvas(canvasId);

    assertTrue(canvasStore.findDocument(canvasId).isEmpty());
    assertTrue(canvasStore.listNodes(canvasId).isEmpty());
    assertTrue(canvasStore.listGroups(canvasId).isEmpty());
    assertTrue(resourceRepository.findByCanvasId(canvasId).isEmpty());
    assertEquals(0, jdbc.queryForObject("select count(1) from canvas_function_run", Integer.class));
    assertEquals(
        0, jdbc.queryForObject("select count(1) from canvas_function_resource_pin", Integer.class));
    assertEquals(
        0, jdbc.queryForObject("select count(1) from canvas_command_dedup", Integer.class));
    commandService.deleteCanvas(canvasId);
  }

  /** Run 行只能挂在 Function 节点上，pin 夹具因此需要节点带函数配置。 */
  private static CanvasFunction function() {
    return new CanvasFunction("video.generate", CanvasJson.parseObject("{}"));
  }

  private CanvasCommandResult apply(UUID canvasId, List<CanvasCommand> commands) {
    return commandService.applyCommands(canvasId, UUID.randomUUID(), commands);
  }

  private CanvasCommandResult apply(
      UUID canvasId, UUID idempotencyKey, List<CanvasCommand> commands) {
    return commandService.applyCommands(canvasId, idempotencyKey, commands);
  }

  private CanvasSnapshot findSnapshot(UUID canvasId) {
    return queryService.findSnapshot(canvasId).orElseThrow();
  }

  /** 当前接受位置由去重表记录，因为 document 的 revision 只反映写入次数。 */
  private long recordedAcceptedRevision(UUID canvasId) {
    return jdbc.queryForObject(
        "select coalesce(max(accepted_revision), -1) from canvas_command_dedup where canvas_id = ?",
        Long.class,
        canvasId);
  }

  /** 直接写入一条已终止 Run 与一条 INPUT pin，用于验证 pin 保护与清理路径。 */
  private UUID addPinnedRun(UUID canvasId, UUID nodeId, UUID resourceId) {
    UUID requestId = UUID.randomUUID();
    jdbc.update(
        "insert into canvas_function_run"
            + " (node_id, request_id, status, attempt, state_json, updated_at, created_at)"
            + " values (?, ?, 'CANCELLED', 0, cast(? as jsonb), current_timestamp, current_timestamp)",
        nodeId,
        requestId,
        minimalRunState("CANCELLED"));
    jdbc.update(
        "insert into canvas_function_resource_pin"
            + " (canvas_id, node_id, request_id, role, resource_id) values (?, ?, ?, 'INPUT', ?)",
        canvasId,
        nodeId,
        requestId,
        resourceId);
    return requestId;
  }

  private UUID addBlob() {
    UUID blobId = UUID.randomUUID();
    jdbc.update(
        "insert into storage_blob (id, sha256, size_bytes, media_type, width, height, ref_count,"
            + " state) values (?, ?, ?, ?, ?, ?, 1, 'ACTIVE')",
        blobId,
        String.format("%064x", blobId.getLeastSignificantBits() & Long.MAX_VALUE),
        3L,
        "image/png",
        1,
        1);
    return blobId;
  }

  private int blobRefCount(UUID blobId) {
    return jdbc.queryForObject(
        "select ref_count from storage_blob where id = ?", Integer.class, blobId);
  }
}
