package fun.fengwk.kkstudio.harness.model.provider.impl.openai.chat;

import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.impl.openai.OpenAiBackendCapabilities;

/**
 * Narrow OpenAI-native capabilities; compatible endpoints do not inherit them by model name alone.
 */
final class OpenAiChatOfficialCapabilities {

  private OpenAiChatOfficialCapabilities() {}

  static boolean isOfficialEndpoint(ProviderDescriptor descriptor) {
    return OpenAiBackendCapabilities.isOfficialEndpoint(descriptor);
  }

  static boolean usesMaxCompletionTokens(String modelId) {
    return modelId.matches("o[134](?:[-._].*)?");
  }
}
