package fun.fengwk.kkstudio.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.function.CanvasJson;

import java.util.List;
import java.util.UUID;

/** {@link CanvasFunction} 的 args/引用校验与 {@link CanvasCommandHash} 请求指纹契约。 */
class CanvasFunctionCommandHashTest {

  /** args 必须是非空 object，因此半结构化数组或标量配置在命令构造时就被拒绝。 */
  @Test
  void functionRejectsNonObjectOrBlankName() {
    CanvasJson.JsonObject args = CanvasJson.parseObject("{}");
    assertThrows(CanvasValidationException.class, () -> new CanvasFunction(" ", args));
    assertEquals("{}", new CanvasFunction("image.crop", args).argsJson());
  }

  /** 引用按首次出现顺序去重，只识别严格 {@code type=resource} 形状。 */
  @Test
  void functionCollectsResourceReferencesInOrder() {
    CanvasFunction function =
        new CanvasFunction(
            "video.generate",
            CanvasJson.parseObject(
                """
                {"prompt":{"segments":[
                  {"type":"resource","nodeId":"00000000-0000-0000-0000-0000000000aa","index":1},
                  {"type":"text","text":"hi"},
                  {"type":"resource","nodeId":"00000000-0000-0000-0000-0000000000bb","index":0},
                  {"type":"resource","nodeId":"00000000-0000-0000-0000-0000000000aa","index":1}
                ]},"parameters":{"ratio":"16:9"}}
                """));

    assertEquals(
        List.of(new CanvasResourceReference(NODE_A, 1), new CanvasResourceReference(NODE_B, 0)),
        function.references());
  }

  /** 形状不合法的引用值不能被当成普通对象跳过，否则既会丢连线也会漏掉引用保护。 */
  @Test
  void functionRejectsMalformedReferenceValues() {
    assertThrows(
        CanvasValidationException.class,
        () ->
            new CanvasFunction(
                    "video.generate",
                    CanvasJson.parseObject("{\"source\":{\"type\":\"resource\",\"nodeId\":\"x\"}}"))
                .references());
    assertThrows(
        CanvasValidationException.class,
        () ->
            new CanvasFunction(
                    "video.generate",
                    CanvasJson.parseObject(
                        "{\"source\":{\"type\":\"resource\",\"nodeId\":\"00000000-0000-0000-0000-0000000000aa\","
                            + "\"index\":0,\"extra\":1}}"))
                .references());
    assertThrows(
        CanvasValidationException.class,
        () ->
            new CanvasFunction(
                    "video.generate",
                    CanvasJson.parseObject(
                        "{\"source\":{\"type\":\"resource\",\"nodeId\":\"00000000-0000-0000-0000-0000000000aa\","
                            + "\"index\":1.5}}"))
                .references());
  }

  /** 请求指纹必须稳定（同一批重试沿用原请求）且区分不同批。 */
  @Test
  void commandHashIsStableAndBatchSensitive() {
    CanvasCommand first = new CanvasCommand.RenameNode(UUID_ZERO, "old", "new");
    CanvasCommand second =
        new CanvasCommand.UpdateNodeTransform(UUID_ZERO, new CanvasTransform(1, 2, 3, 4), null);

    assertEquals(CanvasCommandHash.of(List.of(first)), CanvasCommandHash.of(List.of(first)));
    assertEquals(64, CanvasCommandHash.of(List.of(first)).length());
    assertNotEquals(CanvasCommandHash.of(List.of(first)), CanvasCommandHash.of(List.of(second)));
    assertNotEquals(
        CanvasCommandHash.of(List.of(first, second)), CanvasCommandHash.of(List.of(second, first)));
  }

  private static final UUID UUID_ZERO = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID NODE_A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
  private static final UUID NODE_B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

  /** 覆盖全部命令类型与资源意图的指纹计算，避免新增命令漏进指纹。 */
  @Test
  void commandHashCoversEveryCommandType() {
    CanvasFunction function =
        new CanvasFunction("video.generate", CanvasJson.parseObject("{\"ratio\":\"16:9\"}"));
    List<CanvasCommand> commands =
        List.of(
            new CanvasCommand.CreateNode(
                UUID_ZERO,
                "node",
                new CanvasTransform(0, 0, 10, 10),
                List.of(new CanvasResourceInput.Text("text", "body"))),
            new CanvasCommand.RenameNode(UUID_ZERO, "node", "renamed"),
            new CanvasCommand.SetNodeResources(
                UUID_ZERO,
                List.of(UUID_ZERO),
                List.of(
                    new CanvasResourceInput.Keep(UUID_ZERO),
                    new CanvasResourceInput.Blob("m", UUID_ZERO))),
            new CanvasCommand.SetNodeFunction(UUID_ZERO, null, function),
            new CanvasCommand.SetNodeGroup(UUID_ZERO, null, UUID_ZERO),
            new CanvasCommand.DeleteNode(UUID_ZERO, List.of(), null),
            new CanvasCommand.UpdateNodeTransform(
                UUID_ZERO, new CanvasTransform(1, 1, 1, 1), new CanvasTransform(0, 0, 1, 1)),
            new CanvasCommand.CreateGroup(UUID_ZERO, "group", new CanvasTransform(0, 0, 1, 1)),
            new CanvasCommand.RenameGroup(UUID_ZERO, "group", "group2"),
            new CanvasCommand.UpdateGroupTransform(
                UUID_ZERO, new CanvasTransform(2, 2, 2, 2), null),
            new CanvasCommand.DeleteGroup(UUID_ZERO, List.of(UUID_ZERO)));

    String hash = CanvasCommandHash.of(commands);

    assertEquals(64, hash.length());
    assertFalse(hash.contains("-"));
    assertTrue(hash.matches("[0-9a-f]{64}"));
  }
}
