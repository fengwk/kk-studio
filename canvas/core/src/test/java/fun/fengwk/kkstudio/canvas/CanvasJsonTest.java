package fun.fengwk.kkstudio.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * {@link CanvasJson} 严格解析与规范序列化契约。
 *
 * <p>这些断言是 args 语义比较的基础：JSON 语义相等的值与压缩文本必须稳定，非法输入必须在进入领域前被拒绝。
 */
class CanvasJsonTest {

  /** 对象键顺序不同、数值写法不同但语义相同的值必须相等，这样 Function 前置基线才不会被表示差异误判为冲突。 */
  @Test
  void jsonSemanticEqualityIgnoresKeyOrderAndNumberForm() {
    CanvasJson first = CanvasJson.parse("{\"b\":1,\"a\":[1.0,2e2,true,null]}");
    CanvasJson second = CanvasJson.parse("{\"a\":[1,2e2,true,null],\"b\":1E0}");

    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
    assertEquals("{\"b\":1,\"a\":[1,200,true,null]}", first.write());
    assertEquals(first, CanvasJson.parse(first.write()));
  }

  /** 压缩文本保留插入顺序并转义控制字符，保证持久化文本与 API 文本一致。 */
  @Test
  void writePreservesInsertionOrderAndEscapesControlCharacters() {
    CanvasJson.JsonObject object =
        CanvasJson.parseObject("{\"text\":\"a\\\"b\\n\\u0001\",\"n\":-0.50}");

    assertEquals("{\"text\":\"a\\\"b\\n\\u0001\",\"n\":-0.5}", object.write());
    assertEquals(new CanvasJson.JsonNumber(new BigDecimal("-0.5")), object.values().get("n"));
  }

  /** 重复键、尾随内容、非法转义、控制字符与超深嵌套都属于非法 args，必须直接拒绝。 */
  @Test
  void rejectsNonStrictJson() {
    assertThrows(IllegalArgumentException.class, () -> CanvasJson.parse("{\"a\":1,\"a\":2}"));
    assertThrows(IllegalArgumentException.class, () -> CanvasJson.parse("{\"a\":1}{\"b\":2}"));
    assertThrows(IllegalArgumentException.class, () -> CanvasJson.parse("{\"a\":\"\\x\"}"));
    assertThrows(IllegalArgumentException.class, () -> CanvasJson.parse("{\"a\":\"x\ny\"}"));
    assertThrows(IllegalArgumentException.class, () -> CanvasJson.parseObject("\"text\""));
    assertThrows(IllegalArgumentException.class, () -> CanvasJson.parse("{\"a\":01}"));
    assertThrows(IllegalArgumentException.class, () -> CanvasJson.parse("{\"a\":1e}"));
    assertThrows(IllegalArgumentException.class, () -> CanvasJson.parse(null));
    assertThrows(IllegalArgumentException.class, () -> CanvasJson.parseObject("[1]"));
  }

  /** 深度上限既限制解析，也限制手工构造的值，避免遍历退化成栈溢出。 */
  @Test
  void boundsNestingDepth() {
    String deep = "[".repeat(CanvasJson.MAX_DEPTH + 1) + "1" + "]".repeat(CanvasJson.MAX_DEPTH + 1);
    assertThrows(IllegalArgumentException.class, () -> CanvasJson.parse(deep));

    String allowed = "[".repeat(CanvasJson.MAX_DEPTH) + "1" + "]".repeat(CanvasJson.MAX_DEPTH);
    assertInstanceOf(CanvasJson.JsonArray.class, CanvasJson.parse(allowed));
  }

  /** 输入长度上限限制单个节点配置的大小。 */
  @Test
  void boundsInputLength() {
    String tooLong = "{\"a\":\"" + "x".repeat(CanvasJson.MAX_LENGTH) + "\"}";
    assertThrows(IllegalArgumentException.class, () -> CanvasJson.parse(tooLong));
  }

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
