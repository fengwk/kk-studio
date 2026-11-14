package fun.fengwk.kkstudio.harness.model.limits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.provider.ProviderType;

/**
 * 测试意图：锁定 RequestLimits 值类型的口径不变量（未知即缺省、正值校验）与预设按协议与官方域名匹配（Claude 直连、OpenAI 官方、Gemini
 * inline、其余端点全未知）。
 */
class RequestLimitsTest {

  @Test
  void unknownPresetCarriesNoValues() {
    RequestLimits unknown = RequestLimitsPresets.unknown();
    assertNull(unknown.bodyMaxBytes());
    assertTrue(unknown.image().unknown());
    assertTrue(unknown.document().unknown());
    assertTrue(unknown.audio().unknown());
    assertTrue(unknown.video().unknown());
    assertNull(unknown.visualUnitsMax());
  }

  @Test
  void rejectsNonPositiveValues() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RequestLimits(0L, null, null, null, null, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ImageRequestLimits(-1L, null, null, null, null, null, null));
    assertThrows(IllegalArgumentException.class, () -> new DocumentRequestLimits(null, 0L, null));
  }

  @Test
  void anthropicPresetOnlyMatchesOfficialHost() {
    RequestLimits direct =
        RequestLimitsPresets.resolve(ProviderType.ANTHROPIC, "https://api.anthropic.com/v1");
    assertEquals(128_000_000L, direct.bodyMaxBytes());
    assertEquals(10_000_000L, direct.image().singleEncodedBytes());
    assertEquals(100, direct.image().maxCount());
    assertEquals(8000, direct.image().maxSide());
    assertEquals(20, direct.image().manyImageThreshold());
    assertEquals(3000, direct.image().manyImageMaxSide());
    assertNull(direct.image().singleRawBytes());

    // 代理与私有网关全部未知
    assertTrue(
        RequestLimitsPresets.resolve(ProviderType.ANTHROPIC, "https://proxy.example.com/v1")
            .image()
            .unknown());
    assertNull(
        RequestLimitsPresets.resolve(ProviderType.ANTHROPIC, "https://proxy.example.com/v1")
            .bodyMaxBytes());
  }

  @Test
  void openAiPresetMatchesOfficialHostWithLessThanNormalized() {
    RequestLimits official =
        RequestLimitsPresets.resolve(ProviderType.OPENAI, "https://api.openai.com/v1");
    // 不推广为通用 body 限制
    assertNull(official.bodyMaxBytes());
    assertEquals(512_000_000L, official.image().totalBytes());
    assertEquals(1500, official.image().maxCount());
    // 官方「单个 < 50,000,000 bytes」归一为 49,999,999
    assertEquals(49_999_999L, official.document().singleRawBytes());
    assertEquals(50_000_000L, official.document().totalRawBytes());

    assertTrue(
        RequestLimitsPresets.resolve(ProviderType.OPENAI, "https://gateway.corp.internal/v1")
            .document()
            .unknown());
  }

  @Test
  void responsesPresetSharesOpenAiOfficialHost() {
    RequestLimits official =
        RequestLimitsPresets.resolve(ProviderType.OPENAI_RESPONSES, "https://api.openai.com/v1");
    assertEquals(512_000_000L, official.image().totalBytes());
  }

  @Test
  void geminiPresetMatchesGoogleInlineHost() {
    RequestLimits inline =
        RequestLimitsPresets.resolve(
            ProviderType.GOOGLE, "https://generativelanguage.googleapis.com/v1beta");
    assertEquals(20_000_000L, inline.bodyMaxBytes());
    assertTrue(inline.image().unknown());

    assertTrue(
        RequestLimitsPresets.resolve(ProviderType.GOOGLE, "https://gemini.example-mirror.dev")
            .image()
            .unknown());
    assertNull(
        RequestLimitsPresets.resolve(ProviderType.GOOGLE, "https://gemini.example-mirror.dev")
            .bodyMaxBytes());
  }
}
