package fun.fengwk.kkstudio.harness.model.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.provider.ProviderType;

class PromptCacheCapabilitiesTest {

  @Test
  void resolvesModernOfficialOpenAiModesAndKeepsProxyResponsesShortOnly() {
    PromptCacheCapabilities responses =
        PromptCacheCapabilities.resolve(
            ProviderType.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-5.6");
    assertEquals(PromptCacheState.CONFIGURABLE, responses.promptCacheState());
    assertFalse(responses.automaticCache());
    assertTrue(responses.supports(PromptCacheRetention.NONE));
    assertEquals(1800L, responses.reliableWriteTtlSeconds(PromptCacheRetention.SHORT));
    PromptCacheOption longOption =
        responses.options().stream()
            .filter(option -> option.retention() == PromptCacheRetention.LONG)
            .findFirst()
            .orElseThrow();
    assertEquals(43200L, longOption.ttlSeconds());
    assertEquals(2.5, longOption.writeMultiplier());

    PromptCacheCapabilities proxy =
        PromptCacheCapabilities.resolve(
            ProviderType.OPENAI_RESPONSES, "https://proxy.example/v1", "gpt-5.6");
    assertEquals(PromptCacheState.CONFIGURABLE, proxy.promptCacheState());
    assertEquals(1, proxy.options().size());
    assertTrue(proxy.supports(PromptCacheRetention.SHORT));
    assertFalse(proxy.supports(PromptCacheRetention.LONG));
    assertNull(proxy.reliableWriteTtlSeconds(PromptCacheRetention.SHORT));
  }

  @Test
  void separatesProviderManagedRoutesFromUnmanagedCompatibleChatEndpoints() {
    PromptCacheCapabilities legacy =
        PromptCacheCapabilities.resolve(
            ProviderType.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-5.5");
    assertEquals(PromptCacheState.CONFIGURABLE, legacy.promptCacheState());
    assertEquals(86400L, legacy.reliableWriteTtlSeconds(PromptCacheRetention.LONG));
    PromptCacheCapabilities unknown =
        PromptCacheCapabilities.resolve(
            ProviderType.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-5.3");
    assertEquals(PromptCacheState.CONFIGURABLE, unknown.promptCacheState());
    assertFalse(unknown.supports(PromptCacheRetention.LONG));

    // 兼容 Chat 端点无法确认上游缓存行为，既不控制也不声称由服务商自动管理。
    PromptCacheCapabilities chatProxy =
        PromptCacheCapabilities.resolve(ProviderType.OPENAI, "https://proxy.example/v1", "custom");
    assertEquals(PromptCacheState.UNMANAGED, chatProxy.promptCacheState());
    assertFalse(chatProxy.automaticCache());
    assertTrue(chatProxy.options().isEmpty());
    assertEquals(PromptCacheRetention.NONE, chatProxy.defaultRetention());

    PromptCacheCapabilities gemini =
        PromptCacheCapabilities.resolve(
            ProviderType.GOOGLE, "https://generativelanguage.googleapis.com", "gemini-2.5-pro");
    assertEquals(PromptCacheState.PROVIDER_MANAGED, gemini.promptCacheState());
    assertTrue(gemini.automaticCache());
    assertTrue(gemini.options().isEmpty());
    assertEquals(PromptCacheRetention.NONE, gemini.defaultRetention());
  }

  @Test
  void doesNotApplyClaudeTtlToMiniMaxAndRecognizesProviderManagedStableModel() {
    PromptCacheCapabilities preview =
        PromptCacheCapabilities.resolve(
            ProviderType.ANTHROPIC,
            "https://api.minimax.cn/anthropic",
            "MiniMax-M3.1-Flash-Preview");
    assertEquals(PromptCacheState.CONFIGURABLE, preview.promptCacheState());
    assertNull(preview.reliableWriteTtlSeconds(PromptCacheRetention.LONG));
    assertFalse(preview.supports(PromptCacheRetention.LONG));
    assertTrue(preview.supports(PromptCacheRetention.SHORT));

    PromptCacheCapabilities stable =
        PromptCacheCapabilities.resolve(
            ProviderType.ANTHROPIC, "https://api.minimax.cn/anthropic", "MiniMax-M3.1-Flash");
    assertEquals(PromptCacheState.PROVIDER_MANAGED, stable.promptCacheState());
    assertTrue(stable.automaticCache());
    assertTrue(stable.options().isEmpty());
    assertEquals(PromptCacheRetention.NONE, stable.defaultRetention());

    PromptCacheCapabilities unknown =
        PromptCacheCapabilities.resolve(
            ProviderType.ANTHROPIC, "https://proxy.example/anthropic", "claude-compatible");
    assertEquals(PromptCacheState.CONFIGURABLE, unknown.promptCacheState());
    assertFalse(unknown.supports(PromptCacheRetention.LONG));
    PromptCacheCapabilities officialClaude =
        PromptCacheCapabilities.resolve(
            ProviderType.ANTHROPIC, "https://api.anthropic.com/v1", "claude-sonnet");
    assertEquals(PromptCacheState.CONFIGURABLE, officialClaude.promptCacheState());
    assertEquals(3600L, officialClaude.reliableWriteTtlSeconds(PromptCacheRetention.LONG));
  }
}
