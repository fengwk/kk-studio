package fun.fengwk.kkstudio.core.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.studio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.studio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.studio.canvas.CanvasNodeKind;
import fun.fengwk.kkstudio.studio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.studio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.studio.canvas.NodeTransform;

/**
 * 在权威 PostgreSQL schema 上覆盖最小化的 durable canvas 创建/列表/命令路径。
 *
 * <p>每个测试都运行在全新重置的 schema 中，因此先前测试的 canvas 行不会泄漏到本测试。下面的 {@code request_hash} 哨兵值已被移除——服务端根据规范化后的
 * {@code commandsJson} 计算 hash。
 */
public class DurableCanvasServiceTest extends PostgresSpringTestSupport {

  @Autowired private CanvasCommandService canvasCommandService;
  @Autowired private CanvasQueryService canvasQueryService;
  @Autowired private ObjectMapper objectMapper;

  private static long firstNodeId(CanvasSnapshot snapshot) {
    return snapshot.nodes().get(0).id();
  }

  private static long secondNodeId(CanvasSnapshot snapshot) {
    return snapshot.nodes().get(1).id();
  }

  @Test
  public void shouldCreateListAndApplyMinimalCommands() {
    CanvasDocument created = canvasCommandService.createCanvas("研究画布");
    assertEquals(0L, created.revision());
    assertTrue(
        canvasQueryService.listDocuments().stream().anyMatch(doc -> doc.id() == created.id()));

    String commands =
        "[{\"type\":\"create_text_node\",\"name\":\"笔记\",\"text\":\"hello\\nwith\\\"quote\\\"\","
            + "\"x\":10,\"y\":20,\"width\":200,\"height\":120},"
            + "{\"type\":\"create_generate_text_node\",\"name\":\"生成\",\"prompt\":\"写摘要\","
            + "\"x\":300,\"y\":20,\"width\":280,\"height\":180}]";
    CanvasSnapshot snapshot =
        canvasCommandService.applyCommands(created.id(), 0L, "cmd-" + created.id(), commands);
    assertEquals(1L, snapshot.document().revision());
    assertEquals(2, snapshot.nodes().size());
    assertTrue(snapshot.nodes().stream().anyMatch(node -> CanvasNodeKind.FUNCTION == node.kind()));
  }

  @Test
  public void textNodeDataJsonRoundTripsControlCharacters() throws Exception {
    CanvasDocument created = canvasCommandService.createCanvas("ctl");
    String tricky = "line1\nline2\t\"quoted\"\\back";
    String commands =
        "[{\"type\":\"create_text_node\",\"name\":\"a\",\"text\":"
            + objectMapper.writeValueAsString(tricky)
            + ",\"x\":0,\"y\":0,\"width\":100,\"height\":100}]";
    CanvasSnapshot snap = canvasCommandService.applyCommands(created.id(), 0L, "ctl-cmd", commands);
    JsonNode data = objectMapper.readTree(snap.nodes().get(0).dataJson());
    assertEquals(tricky, data.path("text").asText());
  }

  @Test
  public void geometryValidationRejectsNonFiniteAndNonPositive() {
    CanvasDocument created = canvasCommandService.createCanvas("geometry");

    // NaN 的 x 由 JSON token "NaN" 传入。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            canvasCommandService.applyCommands(
                created.id(),
                0L,
                "bad-nan",
                "[{\"type\":\"create_text_node\",\"name\":\"a\",\"text\":\"x\",\"x\":\"NaN\","
                    + "\"y\":0,\"width\":100,\"height\":100}]"));

    // 零宽度。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            canvasCommandService.applyCommands(
                created.id(),
                0L,
                "bad-zero",
                "[{\"type\":\"create_text_node\",\"name\":\"a\",\"text\":\"x\",\"x\":0,"
                    + "\"y\":0,\"width\":0,\"height\":100}]"));

    // move_nodes 更新会拒绝非有限的 x。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            canvasCommandService.applyCommands(
                created.id(),
                0L,
                "bad-inv",
                "[{\"type\":\"move_nodes\",\"updates\":[{\"id\":1,\"x\":\"NaN\",\"y\":0}]}]"));
  }

  @Test
  public void hardDeleteRemovesNodeAndCascadedLink() {
    CanvasDocument created = canvasCommandService.createCanvas("delete-me");
    CanvasSnapshot afterFirst =
        canvasCommandService.applyCommands(
            created.id(),
            0L,
            "create-a-" + created.id(),
            "[{\"type\":\"create_text_node\",\"name\":\"a\",\"text\":\"hi\",\"x\":0,\"y\":0,"
                + "\"width\":100,\"height\":100}]");
    long revAfterA = afterFirst.document().revision();
    long firstNodeId = firstNodeId(afterFirst);

    CanvasSnapshot afterSecond =
        canvasCommandService.applyCommands(
            created.id(),
            revAfterA,
            "create-b-" + created.id(),
            "[{\"type\":\"create_text_node\",\"name\":\"b\",\"text\":\"ho\",\"x\":100,\"y\":0,"
                + "\"width\":100,\"height\":100}]");
    long revAfterB = afterSecond.document().revision();
    long secondNodeId = secondNodeId(afterSecond);

    CanvasSnapshot afterLink =
        canvasCommandService.applyCommands(
            created.id(),
            revAfterB,
            "link-" + created.id(),
            "[{\"type\":\"create_link\",\"sourceNodeId\":"
                + firstNodeId
                + ",\"targetNodeId\":"
                + secondNodeId
                + "}]");
    long revAfterLink = afterLink.document().revision();
    assertEquals(1, afterLink.links().size());
    assertEquals(firstNodeId, afterLink.links().get(0).sourceNodeId());

    CanvasSnapshot afterDelete =
        canvasCommandService.applyCommands(
            created.id(),
            revAfterLink,
            "delete-" + created.id(),
            "[{\"type\":\"delete_node\",\"id\":" + firstNodeId + "}]");

    assertEquals(1, afterDelete.nodes().size());
    assertTrue(afterDelete.nodes().stream().noneMatch(n -> n.id() == firstNodeId));
    assertTrue(afterDelete.links().isEmpty(), "ON DELETE CASCADE must remove the link");
  }

  @Test
  public void commandSetEmptyArrayIsRejected() {
    CanvasDocument created = canvasCommandService.createCanvas("empty");
    assertThrows(
        IllegalArgumentException.class,
        () -> canvasCommandService.applyCommands(created.id(), 0L, "cmd-empty", "[]"));
  }

  @Test
  public void commandPayloadMustBeAJsonArray() {
    CanvasDocument created = canvasCommandService.createCanvas("notarray");
    assertThrows(
        IllegalArgumentException.class,
        () -> canvasCommandService.applyCommands(created.id(), 0L, "cmd-obj", "{}"));
  }

  @Test
  public void unknownNodeInLinkIsRejected() {
    CanvasDocument created = canvasCommandService.createCanvas("nope");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            canvasCommandService.applyCommands(
                created.id(),
                0L,
                "bad-link",
                "[{\"type\":\"create_link\",\"sourceNodeId\":99999,\"targetNodeId\":99998}]"));
  }

  @Test
  public void distinctEndpointsRequiredForLink() {
    CanvasDocument created = canvasCommandService.createCanvas("self-loop");
    CanvasSnapshot snap =
        canvasCommandService.applyCommands(
            created.id(),
            0L,
            "self-loop-cmd-" + created.id(),
            "[{\"type\":\"create_text_node\",\"name\":\"a\",\"text\":\"x\",\"x\":0,\"y\":0,"
                + "\"width\":100,\"height\":100}]");
    long id = firstNodeId(snap);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            canvasCommandService.applyCommands(
                created.id(),
                snap.document().revision(),
                "self-link",
                "[{\"type\":\"create_link\",\"sourceNodeId\":"
                    + id
                    + ",\"targetNodeId\":"
                    + id
                    + "}]"));
  }

  @Test
  public void revisionCasAndIdempotencyConflict() {
    CanvasDocument created = canvasCommandService.createCanvas("cas");
    String commands =
        "[{\"type\":\"create_text_node\",\"name\":\"a\",\"text\":\"x\",\"x\":0,\"y\":0,"
            + "\"width\":100,\"height\":100}]";
    CanvasSnapshot first =
        canvasCommandService.applyCommands(created.id(), 0L, "cmd-cas-1", commands);
    assertEquals(1L, first.document().revision());

    // 相同 commandId + 相同 payload：重放返回缓存快照。
    CanvasSnapshot replay =
        canvasCommandService.applyCommands(created.id(), 0L, "cmd-cas-1", commands);
    assertEquals(first.document().revision(), replay.document().revision());
    assertEquals(first.nodes().size(), replay.nodes().size());

    // 相同 commandId + 不同 payload：幂等性冲突（hash 不同）。
    String conflict =
        "[{\"type\":\"create_text_node\",\"name\":\"a\",\"text\":\"y\",\"x\":0,\"y\":0,"
            + "\"width\":100,\"height\":100}]";
    assertThrows(
        IllegalStateException.class,
        () -> canvasCommandService.applyCommands(created.id(), 0L, "cmd-cas-1", conflict));

    // 新 commandId + 过时 revision：revision 冲突。
    assertThrows(
        IllegalStateException.class,
        () -> canvasCommandService.applyCommands(created.id(), 0L, "cmd-cas-2", commands));
  }

  @Test
  public void nodeTransformFiniteValidation() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new NodeTransform(Double.POSITIVE_INFINITY, 0d, 100d, 100d));
    assertThrows(
        IllegalArgumentException.class, () -> new NodeTransform(Double.NaN, 0d, 100d, 100d));
    assertThrows(
        IllegalArgumentException.class,
        () -> new NodeTransform(0d, 0d, Double.NEGATIVE_INFINITY, 100d));
    assertThrows(IllegalArgumentException.class, () -> new NodeTransform(0d, 0d, -1d, 100d));
  }

  @Test
  public void createGenerateTextNodeStoresCanonicalDataJson() throws Exception {
    CanvasDocument created = canvasCommandService.createCanvas("data");
    CanvasSnapshot snap =
        canvasCommandService.applyCommands(
            created.id(),
            0L,
            "data-cmd",
            "[{\"type\":\"create_generate_text_node\",\"name\":\"gen\",\"prompt\":\"p\","
                + "\"x\":0,\"y\":0,\"width\":280,\"height\":180}]");
    assertEquals(1, snap.nodes().size());
    JsonNode data = objectMapper.readTree(snap.nodes().get(0).dataJson());
    assertEquals("system.generate-text", data.path("functionId").asText());
    assertEquals("1", data.path("version").asText());
    assertEquals("p", data.path("prompt").asText());
    assertEquals(0, data.path("configRevision").asLong());
  }

  @Test
  public void unknownCanvasReturnsEmptySnapshot() {
    assertTrue(canvasQueryService.findSnapshot(987654321L).isEmpty());
  }

  @Test
  public void listDocumentsReturnsCleanly() {
    assertNotNull(canvasQueryService.listDocuments());
  }

  @Test
  public void realizedNodeCarriesFiniteGeometry() {
    CanvasDocument created = canvasCommandService.createCanvas("realized");
    CanvasSnapshot snap =
        canvasCommandService.applyCommands(
            created.id(),
            0L,
            "realize-cmd",
            "[{\"type\":\"create_text_node\",\"name\":\"a\",\"text\":\"x\",\"x\":12.5,"
                + "\"y\":-3.25,\"width\":100,\"height\":80}]");
    var n = snap.nodes().get(0);
    assertEquals(new NodeTransform(12.5d, -3.25d, 100d, 80d), n.transform());
    assertEquals(CanvasNodeKind.RESOURCE, n.kind());
    assertEquals("text", n.nodeType());
    assertEquals("a", n.name());
    assertFalse(n.dataJson().isBlank());
    assertTrue(Double.isFinite(n.transform().x()));
    assertTrue(Double.isFinite(n.transform().y()));
    assertTrue(n.transform().width() > 0d);
    assertTrue(n.transform().height() > 0d);
  }
}
