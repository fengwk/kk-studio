package fun.fengwk.kkstudio.core.ai.image.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.convention4j.common.json.JsonUtils;
import org.junit.jupiter.api.Test;

import fun.fengwk.convention4j.common.json.JsonUtils;
import org.junit.jupiter.api.Test;

/**
 * {@link GptImage2Request} 单元测试.
 *
 * @author fengwk
 */
public class GptImage2RequestTest {

  @Test
  public void testSerializeAutoSize() {
    GptImage2Request request = new GptImage2Request();
    String json = JsonUtils.toJson(request);
    assertTrue(json.contains("\"size\":\"\""));
  }

  @Test
  public void testSerializeWidescreenSize() {
    GptImage2Request request = new GptImage2Request();
    request.setSize(GptImage2Size.WIDESCREEN_16_9);
    String json = JsonUtils.toJson(request);
    assertTrue(json.contains("\"size\":\"16:9\""));
  }

  @Test
  public void testDeserializeSizes() {
    assertNull(GptImage2Size.fromValue(null));
    assertEquals(GptImage2Size.AUTO, GptImage2Size.fromValue("auto"));
    assertEquals(GptImage2Size.WIDESCREEN_16_9, GptImage2Size.fromValue("16:9"));
    assertEquals("16:9", GptImage2Size.WIDESCREEN_16_9.toString());
    assertThrows(IllegalArgumentException.class, () -> GptImage2Size.fromValue("2:3"));
  }
}
