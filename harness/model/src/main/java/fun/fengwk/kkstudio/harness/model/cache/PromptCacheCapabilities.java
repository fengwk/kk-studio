package fun.fengwk.kkstudio.harness.model.cache;

import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.impl.openai.OpenAiBackendCapabilities;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Resolves prompt-cache behavior from the active wire protocol, endpoint, and model id. */
public record PromptCacheCapabilities(
    List<PromptCacheOption> options,
    PromptCacheRetention defaultRetention,
    boolean automaticCache) {

  public PromptCacheCapabilities {
    options = List.copyOf(options);
  }

  public static PromptCacheCapabilities resolve(
      ProviderType protocol, String endpoint, String modelId) {
    List<PromptCacheOption> options = new ArrayList<>();
    boolean automatic = false;
    switch (protocol) {
      case OPENAI, OPENAI_RESPONSES -> {
        boolean official = OpenAiBackendCapabilities.isOfficialEndpoint(endpoint);
        boolean responses = protocol == ProviderType.OPENAI_RESPONSES;
        boolean modern = official && OpenAiBackendCapabilities.supportsExplicitCache(modelId);
        if (modern) {
          options.add(option(PromptCacheRetention.NONE, null, null));
          options.add(option(PromptCacheRetention.SHORT, 1800L, 1.25));
          if (responses) {
            options.add(option(PromptCacheRetention.LONG, 43200L, 2.5));
          }
        } else if (official) {
          options.add(option(PromptCacheRetention.SHORT, null, null));
          if (OpenAiBackendCapabilities.supportsLegacyLongCache(modelId)) {
            options.add(option(PromptCacheRetention.LONG, 86400L, null));
          }
        } else if (responses) {
          // Compatible Responses endpoints keep the existing Pi-aligned SHORT key and headers.
          options.add(option(PromptCacheRetention.SHORT, null, null));
        } else {
          automatic = true;
        }
      }
      case ANTHROPIC -> {
        if (isMiniMaxStable(endpoint, modelId)) {
          automatic = true;
        } else if (isMiniMax(endpoint)) {
          // Preview behavior is retained, but Claude TTL claims do not apply to MiniMax.
          options.add(option(PromptCacheRetention.NONE, null, null));
          options.add(option(PromptCacheRetention.SHORT, null, null));
          options.add(option(PromptCacheRetention.LONG, null, null));
        } else if (isOfficialAnthropic(endpoint)) {
          options.add(option(PromptCacheRetention.NONE, null, null));
          options.add(option(PromptCacheRetention.SHORT, 300L, 1.25));
          options.add(option(PromptCacheRetention.LONG, 3600L, 2.0));
        } else {
          options.add(option(PromptCacheRetention.NONE, null, null));
          options.add(option(PromptCacheRetention.SHORT, null, null));
          options.add(option(PromptCacheRetention.LONG, null, null));
        }
      }
      case GOOGLE -> automatic = true;
    }
    PromptCacheRetention defaultRetention =
        options.stream()
            .map(PromptCacheOption::retention)
            .filter(retention -> retention != PromptCacheRetention.NONE)
            .findFirst()
            .orElse(null);
    return new PromptCacheCapabilities(options, defaultRetention, automatic);
  }

  public boolean supports(PromptCacheRetention retention) {
    return options.stream().anyMatch(option -> option.retention() == retention);
  }

  public Long reliableWriteTtlSeconds(PromptCacheRetention retention) {
    for (PromptCacheOption option : options) {
      if (option.retention() == retention) {
        return option.ttlSeconds();
      }
    }
    return null;
  }

  private static PromptCacheOption option(
      PromptCacheRetention retention, Long ttlSeconds, Double writeMultiplier) {
    return new PromptCacheOption(retention, ttlSeconds, writeMultiplier);
  }

  private static boolean isOfficialAnthropic(String endpoint) {
    if (endpoint == null) {
      return false;
    }
    try {
      return "api.anthropic.com".equalsIgnoreCase(URI.create(endpoint).getHost());
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static boolean isMiniMax(String endpoint) {
    if (endpoint == null) {
      return false;
    }
    try {
      String host = URI.create(endpoint).getHost();
      return host != null
          && (host.equalsIgnoreCase("api.minimaxi.com") || host.equalsIgnoreCase("api.minimax.io"));
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static boolean isMiniMaxStable(String endpoint, String modelId) {
    if (!isMiniMax(endpoint) || modelId == null) {
      return false;
    }
    return "MiniMax-M2.5".equalsIgnoreCase(modelId);
  }
}
