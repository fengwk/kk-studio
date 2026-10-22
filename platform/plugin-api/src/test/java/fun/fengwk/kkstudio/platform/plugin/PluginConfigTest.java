package fun.fengwk.kkstudio.platform.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.schema.BooleanSchema;
import fun.fengwk.kkstudio.common.schema.InputSchema;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/** PluginConfig 只承载非负 revision 与严格 JSON object 文本，并有 UTF-8 64 KiB 上限。 */
class PluginConfigTest {

  private static final int LIMIT = PluginConfig.MAX_VALUES_UTF8_BYTES;

  private static final PluginConfigSchema SCHEMA =
      new PluginConfigSchema(
          new InputSchema(null, Map.of("enabled", new BooleanSchema(null)), Set.of(), false),
          "{\"enabled\":true}",
          Map.of());

  @Test
  void acceptsStrictObjectAndExposesComponents() {
    PluginConfig config = new PluginConfig(3, "{\"enabled\":false}");

    assertEquals(3, config.revision());
    assertEquals("{\"enabled\":false}", config.valuesJson());
  }

  @Test
  void rejectsNegativeRevisionAndBlankOrNonObjectValues() {
    assertThrows(IllegalArgumentException.class, () -> new PluginConfig(-1, "{}"));
    assertThrows(IllegalArgumentException.class, () -> new PluginConfig(0, null));
    assertThrows(IllegalArgumentException.class, () -> new PluginConfig(0, ""));
    assertThrows(IllegalArgumentException.class, () -> new PluginConfig(0, "   "));
    assertThrows(IllegalArgumentException.class, () -> new PluginConfig(0, "[]"));
    assertThrows(IllegalArgumentException.class, () -> new PluginConfig(0, "\"text\""));
    assertThrows(IllegalArgumentException.class, () -> new PluginConfig(0, "{"));
  }

  /** 严格 JSON：重复字段与尾随内容都必须在边界被拒绝，不能静默取最后一个值。 */
  @Test
  void rejectsDuplicateKeysAndTrailingTokens() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginConfig(0, "{\"enabled\":true,\"enabled\":false}"));
    assertThrows(
        IllegalArgumentException.class, () -> new PluginConfig(0, "{\"enabled\":true} {}"));
  }

  /** 合法 ASCII JSON 恰好 64 KiB 边界可接受，越界一个字节即拒绝；失败信息固定且不含原始 JSON。 */
  @Test
  void enforcesAsciiUtf8ByteBudgetAtTheBoundary() {
    String atLimit = "{\"name\":\"" + "a".repeat(LIMIT - 11) + "\"}";
    assertEquals(LIMIT, atLimit.getBytes(StandardCharsets.UTF_8).length);
    assertEquals(atLimit, new PluginConfig(0, atLimit).valuesJson());

    String overLimit = "{\"name\":\"" + "a".repeat(LIMIT - 10) + "\"}";
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> new PluginConfig(0, overLimit));
    assertTrue(error.getMessage().contains("must not exceed"));
  }

  /** 多字节字符同样按 UTF-8 字节计：合法中文 JSON 未越界可接受，多一个中文字符即拒绝。 */
  @Test
  void enforcesMultibyteUtf8ByteBudget() {
    int chars = (LIMIT - 11) / 3;
    String atLimit = "{\"name\":\"" + "中".repeat(chars) + "\"}";
    assertTrue(atLimit.getBytes(StandardCharsets.UTF_8).length <= LIMIT);
    assertEquals(atLimit, new PluginConfig(0, atLimit).valuesJson());

    String overLimit = "{\"name\":\"" + "中".repeat(chars + 1) + "\"}";
    assertTrue(overLimit.getBytes(StandardCharsets.UTF_8).length > LIMIT);
    assertThrows(IllegalArgumentException.class, () -> new PluginConfig(0, overLimit));
  }

  /** 字符数已超上限时按字符数快速拒绝，不必先物化字节数组。 */
  @Test
  void rejectsByCharacterCountBeforeEncoding() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> new PluginConfig(0, "a".repeat(LIMIT + 1)));
    assertTrue(error.getMessage().contains("must not exceed"));
  }

  @Test
  void initialConfigUsesSchemaDefaultsAtRevisionZero() {
    PluginConfig config = PluginConfig.initial(SCHEMA);

    assertEquals(0, config.revision());
    assertEquals(SCHEMA.defaultsJson(), config.valuesJson());
  }
}
