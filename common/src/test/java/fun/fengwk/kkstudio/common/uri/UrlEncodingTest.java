package fun.fengwk.kkstudio.common.uri;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** {@link UrlEncoding} 的 RFC 3986 契约：路径段与 query 值都不产生表单语义的 {@code +}。 */
class UrlEncodingTest {

  /** 空格、加号、斜杠与 query 分隔符都按位置无关的规则编码，表单解码方仍还原原文。 */
  @Test
  void encodesReservedCharactersWithoutFormSemantics() {
    assertEquals("a%20b%2Bc%2Fd%26e%3Df", UrlEncoding.encode("a b+c/d&e=f"));
    assertFalse(UrlEncoding.encode("a b").contains("+"));
  }

  /** unreserved 字符保持原样，其余非 ASCII 字节按 UTF-8 大写 %XX 编码。 */
  @Test
  void keepsUnreservedAndEncodesUtf8Bytes() {
    assertEquals("AZaz09-._~", UrlEncoding.encode("AZaz09-._~"));
    assertEquals("%E4%B8%AD", UrlEncoding.encode("中"));
  }

  /** 路径段与 query 两个位置都可用：URI 解析不报错且保留转义，表单解码方还原本值。 */
  @Test
  void isUsableAsPathSegmentAndQueryValue() {
    String value = "/workspace/my dir/a+b.txt";
    String encoded = UrlEncoding.encode(value);

    URI uri = URI.create("http://example.com/p/" + encoded + "?path=" + encoded);
    assertEquals("/p/" + encoded, uri.getRawPath());
    assertEquals("path=" + encoded, uri.getRawQuery());
    assertEquals(value, URLDecoder.decode(encoded, StandardCharsets.UTF_8));
  }

  /** null 不静默变成空串：调用方必须显式决定。 */
  @Test
  void rejectsNull() {
    assertThrows(NullPointerException.class, () -> UrlEncoding.encode(null));
  }
}
