package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsCodec;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * {@link LegacySystemSettings} 第一阶段的定向回归：旧七节 JSON 只交给当前六节 codec 与旧 integrations 精确形状校验，任何缺失 / 未知 /
 * 错误类型 / 重复 / 尾随 / 巨整数都不 coerce，死块非默认明确拒绝，失败只带固定安全消息。
 *
 * <p>六节基线由当前 {@link SystemSettingsCodec} 的 {@link SystemSettings#DEFAULT}
 * 编码得到（六节字段源码未改），再插入资源里按旧源码逐字段记录的 integrations 五个块；因此这里不手写权限规则等无关结构，只用真实基线验证 integrations 边界。
 */
class LegacySystemSettingsTest {

  private static final SystemSettingsCodec CODEC = new SystemSettingsCodec();

  private static final String DEFAULTS_INTEGRATIONS = "legacy-integrations-defaults.json";

  /** 完整旧默认 integrations 可被安全退休，六节聚合等于当前六节 DEFAULT。 */
  @Test
  void decodesTheSixSectionsAndValidatesTheLegacyIntegrationsShape() {
    LegacySystemSettings legacy = LegacySystemSettings.parse(defaultLegacyJson());
    assertEquals(SystemSettings.DEFAULT, legacy.settings());
  }

  /** 顶层必须恰为旧七节；非对象、缺节、多节都拒绝。 */
  @Test
  void rejectsANonExactTopLevelSectionSet() {
    assertRejected(null, LegacySystemSettings.MALFORMED);
    assertRejected("   ", LegacySystemSettings.MALFORMED);
    assertRejected("[]", LegacySystemSettings.SECTIONS);
    assertRejected("null", LegacySystemSettings.SECTIONS);

    ObjectNode missing = defaultRoot();
    missing.remove("advanced");
    assertRejected(JsonValues.write(missing), LegacySystemSettings.SECTIONS);

    ObjectNode extra = defaultRoot();
    extra.putObject("unknownSection");
    assertRejected(JsonValues.write(extra), LegacySystemSettings.SECTIONS);
  }

  /** integrations 必须恰为五块且每块是对象；null 块与错误类型都拒绝。 */
  @Test
  void rejectsNonExactOrNonObjectIntegrationBlocks() {
    ObjectNode integrationsNull = defaultRoot();
    integrationsNull.putNull("integrations");
    assertRejected(JsonValues.write(integrationsNull), LegacySystemSettings.INTEGRATIONS_OBJECT);

    ObjectNode missingBlock = defaultRoot();
    ((ObjectNode) missingBlock.get("integrations")).remove("seedance");
    assertRejected(JsonValues.write(missingBlock), LegacySystemSettings.INTEGRATIONS_BLOCKS);

    ObjectNode extraBlock = defaultRoot();
    ((ObjectNode) extraBlock.get("integrations")).putObject("unknown");
    assertRejected(JsonValues.write(extraBlock), LegacySystemSettings.INTEGRATIONS_BLOCKS);

    ObjectNode nullBlock = defaultRoot();
    ((ObjectNode) nullBlock.get("integrations")).putNull("minimaxH3");
    assertRejected(JsonValues.write(nullBlock), LegacySystemSettings.MINIMAX_H3_BLOCK);

    ObjectNode scalarBlock = defaultRoot();
    ((ObjectNode) scalarBlock.get("integrations")).put("seedance", "not-an-object");
    assertRejected(JsonValues.write(scalarBlock), LegacySystemSettings.SEEDANCE_BLOCK);
  }

  /** 各块必须携带精确字段集；缺字段或多字段都拒绝。 */
  @Test
  void rejectsMissingOrUnknownFieldsWithinABlock() {
    ObjectNode missingField = defaultRoot();
    ((ObjectNode) integrations(missingField).get("minimaxH3")).remove("comfyMaxWaitMillis");
    assertRejected(JsonValues.write(missingField), LegacySystemSettings.MINIMAX_H3_BLOCK);

    ObjectNode unknownField = defaultRoot();
    ((ObjectNode) integrations(unknownField).get("minimaxH3")).put("unknown", 1);
    assertRejected(JsonValues.write(unknownField), LegacySystemSettings.MINIMAX_H3_BLOCK);
  }

  /** boolean / string-or-null / integral 类型严格：字符串布尔、字符串数字、浮点、对象都拒绝。 */
  @Test
  void rejectsEveryWrongFieldType() {
    assertBlockRejected(
        "openCliHub", "enabled", "\"true\"", LegacySystemSettings.OPENCLI_HUB_BLOCK);
    assertBlockRejected(
        "openCliHub", "streamBufferBytes", "1.5", LegacySystemSettings.OPENCLI_HUB_BLOCK);
    assertBlockRejected("seedance", "retry", "\"0\"", LegacySystemSettings.SEEDANCE_BLOCK);
    assertBlockRejected(
        "minimaxH3", "promptMaxWaitMillis", "\"600000\"", LegacySystemSettings.MINIMAX_H3_BLOCK);
    assertBlockRejected("gptImage2", "paidEnabled", "1", LegacySystemSettings.GPT_IMAGE_2_BLOCK);
    assertBlockRejected("comfyui", "baseUrl", "5", LegacySystemSettings.COMFYUI_BLOCK);
    assertBlockRejected(
        "comfyui", "connectTimeoutMillis", "true", LegacySystemSettings.COMFYUI_BLOCK);
  }

  /** 超出 long 或 int 的巨整数绝不 coerce 成 0 / 截断。 */
  @Test
  void rejectsIntegersThatDoNotFitTheirDeclaredWidth() {
    ObjectNode beyondLong = defaultRoot();
    ((ObjectNode) integrations(beyondLong).get("minimaxH3"))
        .put("comfyMaxWaitMillis", new BigInteger("99999999999999999999999999"));
    assertRejected(JsonValues.write(beyondLong), LegacySystemSettings.MINIMAX_H3_BLOCK);

    ObjectNode beyondInt = defaultRoot();
    ((ObjectNode) integrations(beyondInt).get("openCliHub"))
        .put("streamBufferBytes", 3_000_000_000L);
    assertRejected(JsonValues.write(beyondInt), LegacySystemSettings.OPENCLI_HUB_BLOCK);
  }

  /** 重复字段与尾随 token 在严格解析边界拒绝。 */
  @Test
  void rejectsDuplicateFieldsAndTrailingTokens() {
    assertRejected("{\"a\":1,\"a\":2}", LegacySystemSettings.MALFORMED);
    assertRejected(defaultLegacyJson() + " 0", LegacySystemSettings.MALFORMED);
  }

  /** 六节自身非法时交给当前 codec 的失败收敛为固定消息，不回显原始值也不带 cause。 */
  @Test
  void rejectsSixSectionsThatTheCurrentCodecRefuses() {
    ObjectNode invalid = defaultRoot();
    ((ObjectNode) invalid.get("aiRuntime")).put("retryMaxRetries", -1);
    assertRejected(JsonValues.write(invalid), LegacySystemSettings.SIX_SECTIONS_REJECTED);
  }

  /** 无消费者的旧 comfyui 块只接受旧 DEFAULT 退休：任何非默认值都明确拒绝。 */
  @Test
  void rejectsANonDefaultLegacyComfyuiBlock() {
    ObjectNode enabled = defaultRoot();
    ((ObjectNode) integrations(enabled).get("comfyui")).put("enabled", true);
    assertRejected(JsonValues.write(enabled), LegacySystemSettings.COMFYUI_BLOCK_NOT_DEFAULT);

    ObjectNode budget = defaultRoot();
    ((ObjectNode) integrations(budget).get("comfyui")).put("connectTimeoutMillis", 10_001L);
    assertRejected(JsonValues.write(budget), LegacySystemSettings.COMFYUI_BLOCK_NOT_DEFAULT);

    ObjectNode baseUrl = defaultRoot();
    ((ObjectNode) integrations(baseUrl).get("comfyui")).put("baseUrl", "http://legacy-comfy:8188");
    assertRejected(JsonValues.write(baseUrl), LegacySystemSettings.COMFYUI_BLOCK_NOT_DEFAULT);
  }

  private static void assertBlockRejected(
      String block, String field, String jsonValue, String message) {
    ObjectNode root = defaultRoot();
    ((ObjectNode) integrations(root).get(block)).set(field, JsonValues.readTree(jsonValue));
    assertRejected(JsonValues.write(root), message);
  }

  private static void assertRejected(String json, String message) {
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> LegacySystemSettings.parse(json));
    assertEquals(message, error.getMessage());
    assertNull(error.getCause());
  }

  private static ObjectNode defaultRoot() {
    ObjectNode root = (ObjectNode) JsonValues.readTree(defaultLegacyJson());
    return root;
  }

  private static ObjectNode integrations(ObjectNode root) {
    return (ObjectNode) root.get("integrations");
  }

  private static String defaultLegacyJson() {
    return legacyJsonWithIntegrations(fixture(DEFAULTS_INTEGRATIONS));
  }

  /** 用当前六节 DEFAULT 编码得到六节基线，再插入资源记录的旧 integrations 块。 */
  static String legacyJsonWithIntegrations(String integrationsJson) {
    ObjectNode root = (ObjectNode) JsonValues.readTree(CODEC.encode(SystemSettings.DEFAULT));
    root.set("integrations", JsonValues.readTree(integrationsJson));
    return JsonValues.write(root);
  }

  static String fixture(String name) {
    try (InputStream in = LegacySystemSettingsTest.class.getResourceAsStream(name)) {
      assertNotNull(in, "missing fixture " + name);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}
