package fun.fengwk.kkstudio.harness.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.api.tool.ToolEditReview;
import fun.fengwk.kkstudio.harness.environment.capability.ExpectedFile;

/** {@link ToolEditReviewJsonCodec} 与 {@link ToolEditReview} / {@link ExpectedFile} 的严格编解码和约束测试。 */
class ToolEditReviewJsonCodecTest {

  private static final String VALID_SHA256 =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final String VALID_PATH = "/srv/repo/src/App.java";
  private static final String VALID_DIFF = "-1|old line\n+1|new line";
  private static final String VALID_JSON =
      "{\"path\":\""
          + VALID_PATH
          + "\",\"diff\":\"-1|old line\\n+1|new line\",\"sha256\":\""
          + VALID_SHA256
          + "\"}";
  private static final String VALID_LOSSY_JSON =
      "{\"path\":\""
          + VALID_PATH
          + "\",\"diff\":\"-1|old line\\n+1|new line\",\"sha256\":\""
          + VALID_SHA256
          + "\",\"lossyDecode\":{\"encoding\":\"GB18030\",\"invalidSequences\":3}}";

  private final ToolEditReviewJsonCodec codec = new ToolEditReviewJsonCodec();

  @Test
  void roundTripsValidReviewExactJson() {
    ToolEditReview review =
        new ToolEditReview(new ExpectedFile(VALID_PATH, VALID_SHA256), VALID_DIFF, null);

    ObjectNode node = codec.encodeNode(review);
    assertEquals(VALID_PATH, node.get("path").stringValue());
    assertEquals(VALID_DIFF, node.get("diff").stringValue());
    assertEquals(VALID_SHA256, node.get("sha256").stringValue());
    assertFalse(node.has("lossyDecode"));

    ToolEditReview decoded = codec.decode(VALID_JSON);
    assertEquals(review, decoded);
    assertEquals(review, codec.decodeNode(node));
  }

  @Test
  void roundTripsLossyDecodeAndOmitsItWhenAbsent() {
    ToolEditReview review =
        new ToolEditReview(
            new ExpectedFile(VALID_PATH, VALID_SHA256),
            VALID_DIFF,
            new ToolEditReview.LossyDecode("GB18030", 3));

    ObjectNode node = codec.encodeNode(review);
    assertEquals("GB18030", node.get("lossyDecode").get("encoding").stringValue());
    assertEquals(3, node.get("lossyDecode").get("invalidSequences").intValue());
    assertEquals(VALID_LOSSY_JSON, InvocationJsonSupport.write(node, "editReview"));
    assertEquals(review, codec.decode(VALID_LOSSY_JSON));
    assertEquals(review, codec.decodeNode(node));
  }

  @Test
  void roundTripsNewFileReviewWithoutSha() {
    ToolEditReview review =
        new ToolEditReview(new ExpectedFile(VALID_PATH, null), "+1|new line", null);

    assertEquals(
        "{\"path\":\"" + VALID_PATH + "\",\"diff\":\"+1|new line\",\"sha256\":null}",
        InvocationJsonSupport.write(codec.encodeNode(review), "editReview"));
    assertEquals(review, codec.decode(codec.encodeNode(review).toString()));
  }

  @Test
  void expectedFileEnforcesPathAndShaFormat() {
    assertThrows(NullPointerException.class, () -> new ExpectedFile(null, VALID_SHA256));
    assertNull(new ExpectedFile(VALID_PATH, null).sha256());
    assertThrows(IllegalArgumentException.class, () -> new ExpectedFile("", VALID_SHA256));
    assertThrows(IllegalArgumentException.class, () -> new ExpectedFile("   ", VALID_SHA256));

    // Sha256 必须是 64 位小写 hex
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExpectedFile(VALID_PATH, VALID_SHA256.toUpperCase()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExpectedFile(VALID_PATH, VALID_SHA256.substring(1)));
    assertThrows(
        IllegalArgumentException.class, () -> new ExpectedFile(VALID_PATH, VALID_SHA256 + "0"));
    assertThrows(
        IllegalArgumentException.class, () -> new ExpectedFile(VALID_PATH, "g".repeat(64)));
  }

  @Test
  void toolEditReviewEnforcesDiffAndLossyDecodeConstraints() {
    ExpectedFile expectedFile = new ExpectedFile(VALID_PATH, VALID_SHA256);
    assertThrows(NullPointerException.class, () -> new ToolEditReview(null, VALID_DIFF, null));
    assertThrows(NullPointerException.class, () -> new ToolEditReview(expectedFile, null, null));
    assertThrows(IllegalArgumentException.class, () -> new ToolEditReview(expectedFile, "", null));

    assertThrows(NullPointerException.class, () -> new ToolEditReview.LossyDecode(null, 1));
    assertThrows(IllegalArgumentException.class, () -> new ToolEditReview.LossyDecode("   ", 1));
    assertThrows(IllegalArgumentException.class, () -> new ToolEditReview.LossyDecode("UTF-8", 0));
    assertThrows(IllegalArgumentException.class, () -> new ToolEditReview.LossyDecode("UTF-8", -1));
  }

  @Test
  void rejectsMalformedDocumentBoundaries() {
    assertThrows(NullPointerException.class, () -> codec.encodeNode(null));
    assertThrows(NullPointerException.class, () -> codec.decode(null));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(""));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("   "));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("\"string\""));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("123"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(VALID_JSON + " trailing"));
  }

  @Test
  void rejectsMissingOrUnknownFields() {
    // 缺失字段
    assertInvalid("{\"diff\":\"" + VALID_DIFF + "\",\"sha256\":\"" + VALID_SHA256 + "\"}");
    assertInvalid("{\"path\":\"" + VALID_PATH + "\",\"sha256\":\"" + VALID_SHA256 + "\"}");
    assertInvalid("{\"path\":\"" + VALID_PATH + "\",\"diff\":\"" + VALID_DIFF + "\"}");

    // 含有未知字段
    assertInvalid(
        "{\"path\":\""
            + VALID_PATH
            + "\",\"diff\":\""
            + VALID_DIFF
            + "\",\"sha256\":\""
            + VALID_SHA256
            + "\",\"extra\":1}");
  }

  @Test
  void rejectsInvalidFieldValues() {
    // null 字段
    assertInvalid(
        "{\"path\":null,\"diff\":\"" + VALID_DIFF + "\",\"sha256\":\"" + VALID_SHA256 + "\"}");
    assertInvalid(
        "{\"path\":\"" + VALID_PATH + "\",\"diff\":null,\"sha256\":\"" + VALID_SHA256 + "\"}");
    // 非字符串字段
    assertInvalid(
        "{\"path\":123,\"diff\":\"" + VALID_DIFF + "\",\"sha256\":\"" + VALID_SHA256 + "\"}");
    assertInvalid(
        "{\"path\":\"" + VALID_PATH + "\",\"diff\":true,\"sha256\":\"" + VALID_SHA256 + "\"}");
    assertInvalid(
        "{\"path\":\"" + VALID_PATH + "\",\"diff\":\"" + VALID_DIFF + "\",\"sha256\":{}}");

    // 空 diff 或非法 sha256 语法
    assertInvalid(
        "{\"path\":\"" + VALID_PATH + "\",\"diff\":\"\",\"sha256\":\"" + VALID_SHA256 + "\"}");
    assertInvalid(
        "{\"path\":\"" + VALID_PATH + "\",\"diff\":\"" + VALID_DIFF + "\",\"sha256\":\"bad-sha\"}");
    assertInvalid(
        "{\"path\":\"   \",\"diff\":\"" + VALID_DIFF + "\",\"sha256\":\"" + VALID_SHA256 + "\"}");
  }

  @Test
  void rejectsMalformedLossyDecode() {
    // 显式 null、非 object、缺失或未知字段、空编码、非正替换数都必须拒绝。
    assertInvalid(lossyJson("null"));
    assertInvalid(lossyJson("[]"));
    assertInvalid(lossyJson("{\"encoding\":\"GB18030\"}"));
    assertInvalid(lossyJson("{\"invalidSequences\":3}"));
    assertInvalid(lossyJson("{\"encoding\":\"GB18030\",\"invalidSequences\":3,\"extra\":1}"));
    assertInvalid(lossyJson("{\"encoding\":\"   \",\"invalidSequences\":3}"));
    assertInvalid(lossyJson("{\"encoding\":\"GB18030\",\"invalidSequences\":0}"));
    assertInvalid(lossyJson("{\"encoding\":\"GB18030\",\"invalidSequences\":-1}"));
    assertInvalid(lossyJson("{\"encoding\":\"GB18030\",\"invalidSequences\":1.5}"));
    assertInvalid(lossyJson("{\"encoding\":\"GB18030\",\"invalidSequences\":\"3\"}"));
    assertInvalid(lossyJson("{\"encoding\":7,\"invalidSequences\":3}"));
  }

  private static String lossyJson(String lossyDecode) {
    return "{\"path\":\""
        + VALID_PATH
        + "\",\"diff\":\""
        + VALID_DIFF
        + "\",\"sha256\":\""
        + VALID_SHA256
        + "\",\"lossyDecode\":"
        + lossyDecode
        + "}";
  }

  private void assertInvalid(String json) {
    assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
  }
}
