package fun.fengwk.kkstudio.canvas;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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

  /** 输入长度上限限制单个节点配置的大小；恰好等于预算的输入仍可解析。 */
  @Test
  void boundsInputLength() {
    String tooLong = "{\"a\":\"" + "x".repeat(CanvasJson.MAX_LENGTH) + "\"}";
    assertThrows(IllegalArgumentException.class, () -> CanvasJson.parse(tooLong));

    String content = "x".repeat(CanvasJson.MAX_LENGTH - 8);
    String exact = "{\"a\":\"" + content + "\"}";
    assertEquals(CanvasJson.MAX_LENGTH, exact.length());
    assertEquals(exact, CanvasJson.parse(exact).write());
  }

  /** 规范化 golden 样本：合法输入写成稳定 canonical，并能原样回读相等（读写闭合）。 */
  @Test
  void matchesCanonicalGoldenSamples() throws IOException {
    for (CanvasJson item : fixtureArray("canonicalizations").values()) {
      CanvasJson.JsonObject entry = (CanvasJson.JsonObject) item;
      String input = text(entry, "input");
      String canonical = text(entry, "canonical");
      CanvasJson value = CanvasJson.parse(input);

      assertEquals(canonical, value.write(), input);
      assertEquals(value, CanvasJson.parse(canonical), input);
    }
  }

  /** 非法 golden 样本：这些历史非法输入继续被严格拒绝，不做静默兜底。 */
  @Test
  void rejectsGoldenInvalidSamples() throws IOException {
    for (CanvasJson item : fixtureArray("rejections").values()) {
      String input = ((CanvasJson.JsonText) item).value();
      assertThrows(IllegalArgumentException.class, () -> CanvasJson.parse(input), input);
    }
  }

  /** B06：短指数输入展开后超过预算时必须在分配前拒绝（解析与手工构造一致），不依赖 Jackson 的 scale 限制。 */
  @Test
  void rejectsNumberExpansionBeyondLengthBudget() {
    assertThrows(IllegalArgumentException.class, () -> CanvasJson.parse("1e65536"));
    assertThrows(IllegalArgumentException.class, () -> CanvasJson.parse("1e-65535"));
    assertThrows(IllegalArgumentException.class, () -> CanvasJson.parse("{\"n\":1e-65529}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CanvasJson.JsonNumber(new BigDecimal("1e65536")).write());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasJson.JsonNumber(new BigDecimal("1".repeat(CanvasJson.MAX_LENGTH + 1)))
                .write());
  }

  /** B06：不再受 Jackson plain BigDecimal 的 ±9999 scale 限制，正负大指数在预算内仍可写回并回读。 */
  @Test
  void acceptsLargeExponentWithinLengthBudget() {
    assertRoundTrip("1e10000", 10_001);
    assertRoundTrip("1e-10000", 10_002);
    assertRoundTrip("1e65535", CanvasJson.MAX_LENGTH);
    assertRoundTrip("1e-65534", CanvasJson.MAX_LENGTH);
  }

  /** B06：容器结构开销计入同一预算，单值恰好等于预算的容器结构仍可写回。 */
  @Test
  void containerBudgetIncludesStructuralCharacters() {
    CanvasJson value = CanvasJson.parse("{\"n\":1e-65528}");
    String canonical = value.write();

    assertEquals(CanvasJson.MAX_LENGTH, canonical.length());
    assertEquals(value, CanvasJson.parse(canonical));
  }

  /** B06：多个仍在单值预算内的数值累计越界时必须整体拒绝，累计边界两侧都要准确。 */
  @Test
  void rejectsAccumulatedNumberExpansion() {
    // 单值明文各 33000 字符，对象累计 66011 > 预算。
    assertThrows(
        IllegalArgumentException.class, () -> CanvasJson.parse("{\"a\":1e-32998,\"b\":1e-32998}"));
    // 单值明文各 32762 字符，对象累计 65535 ≤ 预算，仍必须接受并闭合。
    CanvasJson fits = CanvasJson.parse("{\"a\":1e-32760,\"b\":1e-32760}");
    assertEquals(65535, fits.write().length());
    assertEquals(fits, CanvasJson.parse(fits.write()));
    // 手工构造的多值累计同样受同一预算约束。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasJson.JsonArray(
                    List.of(
                        new CanvasJson.JsonNumber(new BigDecimal("1".repeat(40_000))),
                        new CanvasJson.JsonNumber(new BigDecimal("1".repeat(40_000)))))
                .write());
  }

  /** B06：长转义串不会放大 canonical，接近预算的合法串仍能稳定写回并回读。 */
  @Test
  void longEscapedStringStaysWithinBudgetAndRoundTrips() {
    String input = "{\"s\":\"" + "\\u0001".repeat(10_000) + "\"}";
    CanvasJson value = CanvasJson.parse(input);

    assertEquals(input, value.write());
    assertEquals(value, CanvasJson.parse(value.write()));
  }

  /** 嵌套深度按容器计数：解析与写回都允许 MAX_DEPTH 层容器、拒绝第 MAX_DEPTH+1 层，空/非空结构一致。 */
  @Test
  void containerDepthIsConsistentBetweenParseAndWrite() {
    String allowedArrays = "[".repeat(CanvasJson.MAX_DEPTH) + "]".repeat(CanvasJson.MAX_DEPTH);
    assertEquals(allowedArrays, CanvasJson.parse(allowedArrays).write());
    String tooDeepArrays =
        "[".repeat(CanvasJson.MAX_DEPTH + 1) + "]".repeat(CanvasJson.MAX_DEPTH + 1);
    assertThrows(IllegalArgumentException.class, () -> CanvasJson.parse(tooDeepArrays));

    String allowedObjects =
        "{\"a\":".repeat(CanvasJson.MAX_DEPTH) + "1" + "}".repeat(CanvasJson.MAX_DEPTH);
    assertEquals(allowedObjects, CanvasJson.parse(allowedObjects).write());
    String tooDeepObjects =
        "{\"a\":".repeat(CanvasJson.MAX_DEPTH + 1) + "1" + "}".repeat(CanvasJson.MAX_DEPTH + 1);
    assertThrows(IllegalArgumentException.class, () -> CanvasJson.parse(tooDeepObjects));

    // 手工构造的同样结构必须与解析同界：MAX_DEPTH 层空数组可写且可回读，第 MAX_DEPTH+1 层拒绝。
    CanvasJson handBuilt = new CanvasJson.JsonNull();
    for (int depth = 0; depth < CanvasJson.MAX_DEPTH; depth++) {
      handBuilt = new CanvasJson.JsonArray(List.of(handBuilt));
    }
    CanvasJson handBuiltAllowed = handBuilt;
    assertDoesNotThrow(handBuiltAllowed::write);
    assertEquals(handBuiltAllowed, CanvasJson.parse(handBuiltAllowed.write()));

    CanvasJson tooDeep = new CanvasJson.JsonArray(List.of(handBuilt));
    assertThrows(IllegalArgumentException.class, tooDeep::write);
  }

  /** 控制字符与对象键统一按小写 hex 转义，保留历史 canonical 字面与命令指纹。 */
  @Test
  void escapesControlCharactersAsLowercaseHexInValuesAndKeys() {
    StringBuilder control = new StringBuilder();
    for (char character = 0; character < 0x20; character++) {
      control.append(character);
    }
    String escapes =
        "\\u0000\\u0001\\u0002\\u0003\\u0004\\u0005\\u0006\\u0007\\b\\t\\n\\u000b\\f\\r"
            + "\\u000e\\u000f\\u0010\\u0011\\u0012\\u0013\\u0014\\u0015\\u0016\\u0017\\u0018\\u0019"
            + "\\u001a\\u001b\\u001c\\u001d\\u001e\\u001f";
    CanvasJson.JsonObject value =
        new CanvasJson.JsonObject(
            Map.of(control.toString(), new CanvasJson.JsonText(control.toString())));

    String canonical = value.write();
    assertEquals("{\"" + escapes + "\":\"" + escapes + "\"}", canonical);
    assertEquals(value, CanvasJson.parse(canonical));
  }

  private static void assertRoundTrip(String number, int expectedLength) {
    CanvasJson value = CanvasJson.parse(number);
    String canonical = value.write();

    assertEquals(expectedLength, canonical.length(), number);
    assertEquals(value, CanvasJson.parse(canonical), number);
  }

  private static CanvasJson.JsonArray fixtureArray(String name) throws IOException {
    try (InputStream stream =
        CanvasJsonTest.class.getResourceAsStream("canonical-json-cases.json")) {
      assertNotNull(stream, "golden fixture must exist");
      CanvasJson.JsonObject fixture =
          CanvasJson.parseObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      return (CanvasJson.JsonArray) fixture.values().get(name);
    }
  }

  private static String text(CanvasJson.JsonObject object, String field) {
    return ((CanvasJson.JsonText) object.values().get(field)).value();
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
