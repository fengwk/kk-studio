package fun.fengwk.kkstudio.canvas.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.canvas.dto.CanvasFunctionCancelRequestDTO;
import fun.fengwk.kkstudio.canvas.dto.CanvasFunctionRunRequestDTO;
import fun.fengwk.kkstudio.canvas.dto.CanvasFunctionVersionDTO;
import fun.fengwk.kkstudio.common.json.JsonValues;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Canvas 函数版本身份与 run/cancel 请求体的 HTTP wire golden。
 *
 * <p>生产 HTTP mapper 仍由 Web 组合根装配；Canvas 模块不依赖 Web，因此本测试使用与生产同一组严格设置的 {@link
 * CanvasHttpJsonTestSupport} mapper 直接序列化真实 DTO。golden 就是后端实际收发的 JSON 文本形态，前端共用同一份资源做严格解码验证。
 *
 * <p>golden 是 UTF-8 原文，测试只比对已提交的资源，绝不在运行时改写。
 */
class CanvasFunctionWireContractTest {

  private static final JsonMapper HTTP_MAPPER = CanvasHttpJsonTestSupport.get();

  private static final String GOLDEN_DIR = "fun/fengwk/kkstudio/canvas/http/";

  /** lowercase 64 位 SHA-256 定义指纹。 */
  private static final String DEFINITION_FINGERPRINT =
      "3c9a6c2b5f1d4e8a7b0c9d2e3f4a5b6c7d8e9f0a1b2c3d4e5f60718293a4b5c6";

  /** canonical lowercase UUID：run/cancel 只按该 requestId 收敛终态。 */
  private static final String REQUEST_ID = "c0ffee00-0000-4000-8000-000000000001";

  /** 测试意图：目录版本身份的 wire 文本与已提交 golden 一致，只含定义指纹。 */
  @Test
  void functionVersionMatchesCommittedWireGolden() throws Exception {
    CanvasFunctionVersionDTO version = new CanvasFunctionVersionDTO();
    version.setDefinitionFingerprint(DEFINITION_FINGERPRINT);

    String json = HTTP_MAPPER.writeValueAsString(version);
    assertWireMatchesGolden("canvas-function-version-http.json", json);

    JsonNode node = HTTP_MAPPER.readTree(json);
    assertEquals(1, node.size());
    assertEquals(DEFINITION_FINGERPRINT, node.get("definitionFingerprint").asString());
  }

  /** 测试意图：start 请求体只含 requestId 与 expectedVersion，且 version 是同一份 golden 片段。 */
  @Test
  void functionRunRequestMatchesCommittedWireGolden() throws Exception {
    CanvasFunctionRunRequestDTO request = new CanvasFunctionRunRequestDTO();
    request.setRequestId(REQUEST_ID);
    request.setExpectedVersion(version());

    String json = HTTP_MAPPER.writeValueAsString(request);
    assertWireMatchesGolden("canvas-function-run-request-http.json", json);

    JsonNode node = HTTP_MAPPER.readTree(json);
    assertTrue(node.has("requestId") && node.has("expectedVersion"), json);
    assertEquals(REQUEST_ID, node.get("requestId").asString());
    assertEquals(
        JsonValues.readTree(readGolden("canvas-function-version-http.json")),
        node.get("expectedVersion"));
  }

  /** 测试意图：cancel 请求体只有 requestId，绝不携带目录版本围栏。 */
  @Test
  void functionCancelRequestMatchesCommittedWireGolden() throws Exception {
    CanvasFunctionCancelRequestDTO request = new CanvasFunctionCancelRequestDTO();
    request.setRequestId(REQUEST_ID);

    String json = HTTP_MAPPER.writeValueAsString(request);
    assertWireMatchesGolden("canvas-function-cancel-request-http.json", json);

    JsonNode node = HTTP_MAPPER.readTree(json);
    assertEquals(1, node.size());
    assertEquals(REQUEST_ID, node.get("requestId").asString());
  }

  /** 与已提交 golden 按 JSON 值比对：属性顺序无关，但仍锁定每个 token 的形态。 */
  private static void assertWireMatchesGolden(String goldenName, String json) {
    assertEquals(
        JsonValues.readTree(readGolden(goldenName)),
        JsonValues.readTree(json),
        goldenName + " must match the production mapper output");
  }

  private static CanvasFunctionVersionDTO version() {
    CanvasFunctionVersionDTO version = new CanvasFunctionVersionDTO();
    version.setDefinitionFingerprint(DEFINITION_FINGERPRINT);
    return version;
  }

  private static String readGolden(String filename) {
    try (InputStream in = new ClassPathResource(GOLDEN_DIR + filename).getInputStream()) {
      return new String(in.readAllBytes(), UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException("failed to read canvas wire golden " + filename, ex);
    }
  }
}
