package fun.fengwk.kkstudio.harness.model.provider.impl.openai;

import fun.fengwk.kkstudio.harness.model.cache.PromptCacheCapabilities;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;

import java.net.URI;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the OpenAI-native protocol capabilities proven for the official endpoint and known
 * models.
 */
public final class OpenAiBackendCapabilities {

  private static final Pattern GPT_MODEL = Pattern.compile("gpt-(\\d+)(?:\\.(\\d+))?(?:[-._].*)?");
  private static final Set<String> EXPLICIT_CACHE_MODEL_LINES = Set.of("5.6", "6.1", "6.2");
  private static final Set<String> LEGACY_LONG_CACHE_MODELS =
      Set.of(
          "gpt-5.5",
          "gpt-5.5-pro",
          "gpt-5.4",
          "gpt-5.2",
          "gpt-5.1-codex-max",
          "gpt-5.1",
          "gpt-5.1-codex",
          "gpt-5.1-codex-mini",
          "gpt-5.1-chat-latest",
          "gpt-5",
          "gpt-5-codex",
          "gpt-4.1");

  private OpenAiBackendCapabilities() {}

  public static boolean isOfficialEndpoint(ProviderDescriptor descriptor) {
    return isOfficialEndpoint(descriptor.endpoint());
  }

  public static boolean isOfficialEndpoint(String endpoint) {
    if (endpoint == null) {
      return false;
    }
    try {
      return "api.openai.com".equalsIgnoreCase(URI.create(endpoint).getHost());
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  public static boolean supportsExplicitCache(String modelId) {
    Matcher matcher = GPT_MODEL.matcher(modelId);
    if (!matcher.matches()) {
      return false;
    }
    try {
      String line =
          Integer.parseInt(matcher.group(1))
              + "."
              + Integer.parseInt(matcher.group(2) == null ? "0" : matcher.group(2));
      return EXPLICIT_CACHE_MODEL_LINES.contains(line);
    } catch (NumberFormatException exception) {
      return false;
    }
  }

  public static boolean supportsLegacyLongCache(String modelId) {
    return LEGACY_LONG_CACHE_MODELS.contains(modelId);
  }

  /**
   * Official Responses requests use a leading developer message so reusable instructions precede
   * input.
   */
  public static boolean usesDeveloperSystemMessage(ProviderDescriptor descriptor, String modelId) {
    return isOfficialEndpoint(descriptor) && supportsExplicitCache(modelId);
  }

  /** Reliable write TTL for the selected, supported retention on known official models. */
  public static Long reliableWriteTtlSeconds(
      ProviderDescriptor descriptor, String modelId, PromptCacheRetention retention) {
    return PromptCacheCapabilities.resolve(descriptor.type(), descriptor.endpoint(), modelId)
        .reliableWriteTtlSeconds(retention);
  }
}
