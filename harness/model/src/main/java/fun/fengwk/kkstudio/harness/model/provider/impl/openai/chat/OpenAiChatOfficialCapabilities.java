package fun.fengwk.kkstudio.harness.model.provider.impl.openai.chat;

import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Narrow OpenAI-native capabilities; compatible endpoints do not inherit them by model name alone.
 */
final class OpenAiChatOfficialCapabilities {

  private static final Pattern GPT_MODEL = Pattern.compile("gpt-(\\d+)(?:\\.(\\d+))?.*");

  private OpenAiChatOfficialCapabilities() {}

  static boolean isOfficialEndpoint(ProviderDescriptor descriptor) {
    try {
      return "api.openai.com".equalsIgnoreCase(URI.create(descriptor.endpoint()).getHost());
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  static boolean usesMaxCompletionTokens(String modelId) {
    return modelId.matches("o[134](?:[-._].*)?");
  }

  static boolean hasThirtyMinuteDefaultCacheWrite(String modelId) {
    Matcher matcher = GPT_MODEL.matcher(modelId);
    if (!matcher.matches()) {
      return false;
    }
    try {
      int major = Integer.parseInt(matcher.group(1));
      int minor = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
      return major > 5 || major == 5 && minor >= 6;
    } catch (NumberFormatException exception) {
      return false;
    }
  }
}
