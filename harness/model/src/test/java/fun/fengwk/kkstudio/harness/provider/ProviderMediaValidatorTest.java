package fun.fengwk.kkstudio.harness.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

/** 测试意图：验证媒体白名单的精确匹配、严格 Base64 data-URI 解析与 Anthropic 精确前缀提取。 */
class ProviderMediaValidatorTest {

  private static final String VALID_PAYLOAD =
      Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4});

  @Test
  @DisplayName("parseBase64DataUri: 严格解析与大小写兼容")
  void testParseBase64DataUri() {
    // 正常 data URI
    String uri = "data:image/png;base64," + VALID_PAYLOAD;
    ProviderMediaValidator.Base64DataUri parsed = ProviderMediaValidator.parseBase64DataUri(uri);
    assertNotNull(parsed);
    assertEquals("image/png", parsed.mediaType());
    assertEquals(VALID_PAYLOAD, parsed.payload());

    // 大写 scheme 与 base64 标记（RFC 2397 大小写不敏感）
    String upperUri = "DATA:IMAGE/JPEG;BASE64," + VALID_PAYLOAD;
    ProviderMediaValidator.Base64DataUri upperParsed =
        ProviderMediaValidator.parseBase64DataUri(upperUri);
    assertNotNull(upperParsed);
    assertEquals("IMAGE/JPEG", upperParsed.mediaType());
    assertEquals(VALID_PAYLOAD, upperParsed.payload());

    // 边界与非法输入
    assertNull(ProviderMediaValidator.parseBase64DataUri(null));
    assertNull(ProviderMediaValidator.parseBase64DataUri(""));
    assertNull(ProviderMediaValidator.parseBase64DataUri("http://example.com/pic.png"));
    assertNull(ProviderMediaValidator.parseBase64DataUri("data:image/png;base64,")); // 空 payload
    assertNull(
        ProviderMediaValidator.parseBase64DataUri("data:;base64," + VALID_PAYLOAD)); // 空 mediaType
    assertNull(
        ProviderMediaValidator.parseBase64DataUri(
            "data:image/png;base64,not-base64!")); // 非法 base64
  }

  @Test
  @DisplayName("extractBase64Data: 媒体类型前缀精确匹配")
  void testExtractBase64Data() {
    String uri = "data:image/png;base64," + VALID_PAYLOAD;

    // 精确匹配才返回 payload
    assertEquals(VALID_PAYLOAD, ProviderMediaValidator.extractBase64Data(uri, "image/png"));

    // 媒体类型或大小写不一致时不接受，空值与非法 payload 返回 null
    assertNull(ProviderMediaValidator.extractBase64Data(uri, "image/jpeg"));
    assertNull(
        ProviderMediaValidator.extractBase64Data(
            "data:IMAGE/PNG;base64," + VALID_PAYLOAD, "image/png"));
    assertNull(ProviderMediaValidator.extractBase64Data("http://example.com", "image/png"));
    assertNull(ProviderMediaValidator.extractBase64Data(null, "image/png"));
    assertNull(ProviderMediaValidator.extractBase64Data("  ", "image/png"));
    assertNull(ProviderMediaValidator.extractBase64Data("data:image/png;base64,", "image/png"));
    assertNull(ProviderMediaValidator.extractBase64Data("data:image/png;base64,???", "image/png"));
  }

  @Test
  @DisplayName("isAllowedMediaType: 白名单精确匹配")
  void testIsAllowedMediaType() {
    assertTrue(
        ProviderMediaValidator.isAllowedMediaType(
            "image/png", ProviderMediaValidator.IMAGE_MEDIA_TYPES));
    assertTrue(
        ProviderMediaValidator.isAllowedMediaType(
            "application/pdf", ProviderMediaValidator.DOCUMENT_MEDIA_TYPES));
    // 大小写规范化由协议在调用前完成，helper 本身不做隐式转换
    assertFalse(
        ProviderMediaValidator.isAllowedMediaType(
            "IMAGE/JPEG", ProviderMediaValidator.IMAGE_MEDIA_TYPES));
    assertFalse(
        ProviderMediaValidator.isAllowedMediaType(
            "image/gif", ProviderMediaValidator.DOCUMENT_MEDIA_TYPES));
    assertFalse(
        ProviderMediaValidator.isAllowedMediaType(null, ProviderMediaValidator.IMAGE_MEDIA_TYPES));
  }
}
