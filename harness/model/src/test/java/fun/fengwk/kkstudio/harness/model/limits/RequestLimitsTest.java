package fun.fengwk.kkstudio.harness.model.limits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.provider.ProviderType;

import java.util.List;

/**
 * 测试意图：锁定 RequestLimits 值类型的口径不变量（未知即缺省、正值校验）、预设按协议与官方域名匹配（Claude 直连、OpenAI 官方、Gemini
 * inline、其余端点全未知）、200k 窗口的图片数量切换，以及合并函数的逐项最严值语义。
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

  @Test
  void claudeDirectSwitchesImageCountAt200kWindow() {
    assertEquals(100, RequestLimitsPresets.claudeDirect(200_000).image().maxCount());
    assertEquals(100, RequestLimitsPresets.claudeDirect(1_000_000).image().maxCount());
    assertEquals(600, RequestLimitsPresets.claudeDirect(128_000).image().maxCount());
    assertThrows(IllegalArgumentException.class, () -> RequestLimitsPresets.claudeDirect(0));
  }

  @Test
  void mergeTakesStrictestKnownValuePerField() {
    RequestLimits strict =
        new RequestLimits(
            1_000L,
            new ImageRequestLimits(null, 100L, 5_000L, 4, null, 3, 2000),
            DocumentRequestLimits.UNKNOWN,
            AudioRequestLimits.UNKNOWN,
            VideoRequestLimits.UNKNOWN,
            null,
            "a",
            "d1");
    RequestLimits loose =
        new RequestLimits(
            10_000L,
            new ImageRequestLimits(2_000L, 500L, 50_000L, null, 8000, null, 3000),
            new DocumentRequestLimits(9_999L, null, 5),
            AudioRequestLimits.UNKNOWN,
            VideoRequestLimits.UNKNOWN,
            7L,
            "a",
            "d1");
    RequestLimits unknown = RequestLimitsPresets.unknown();

    RequestLimits merged = RequestLimitsMerger.merge(List.of(strict, loose, unknown));

    assertEquals(1_000L, merged.bodyMaxBytes());
    // 逐项取最严已知值：不同字段可以来自不同成员
    assertEquals(2_000L, merged.image().singleRawBytes());
    assertEquals(100L, merged.image().singleEncodedBytes());
    assertEquals(5_000L, merged.image().totalBytes());
    assertEquals(4, merged.image().maxCount());
    assertEquals(8000, merged.image().maxSide());
    assertEquals(3, merged.image().manyImageThreshold());
    assertEquals(2000, merged.image().manyImageMaxSide());
    assertEquals(9_999L, merged.document().singleRawBytes());
    assertEquals(5, merged.document().maxPages());
    assertEquals(7L, merged.visualUnitsMax());
  }

  @Test
  void mergeKeepsUnknownWhenNoMemberKnowsAField() {
    RequestLimits partial =
        new RequestLimits(
            null,
            ImageRequestLimits.UNKNOWN,
            new DocumentRequestLimits(null, 8_000L, null),
            AudioRequestLimits.UNKNOWN,
            VideoRequestLimits.UNKNOWN,
            null,
            null,
            null);
    RequestLimits merged =
        RequestLimitsMerger.merge(List.of(partial, RequestLimitsPresets.unknown()));

    assertNull(merged.bodyMaxBytes());
    assertNull(merged.image().singleRawBytes());
    assertEquals(8_000L, merged.document().totalRawBytes());
    assertNull(merged.document().singleRawBytes());
    assertTrue(merged.audio().unknown());
    assertFalse(merged.document().unknown());
  }

  @Test
  void mergeListsDistinctSourcesAndRequiresMembers() {
    RequestLimits first =
        RequestLimitsPresets.resolve(ProviderType.ANTHROPIC, "https://api.anthropic.com");
    RequestLimits second =
        RequestLimitsPresets.resolve(
            ProviderType.GOOGLE, "https://generativelanguage.googleapis.com");

    RequestLimits merged = RequestLimitsMerger.merge(List.of(first, second));
    assertTrue(merged.sources().contains("platform.claude.com"));
    assertTrue(merged.sources().contains("ai.google.dev"));
    assertEquals(RequestLimitsMerger.merge(List.of(first, first)).sources(), first.sources());

    assertThrows(IllegalArgumentException.class, () -> RequestLimitsMerger.merge(List.of()));
  }
}
