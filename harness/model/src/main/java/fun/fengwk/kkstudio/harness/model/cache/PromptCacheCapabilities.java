package fun.fengwk.kkstudio.harness.model.cache;

import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.impl.openai.OpenAiBackendCapabilities;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves prompt-cache behavior from the active wire protocol, endpoint, and model id.
 *
 * <p>{@link PromptCacheState} only states whether Studio can influence the upstream cache; options
 * and default retention keep each protocol's existing encoding contract, and unverified routes are
 * never reported as {@link PromptCacheState#UNSUPPORTED}.
 */
public record PromptCacheCapabilities(
    List<PromptCacheOption> options,
    PromptCacheRetention defaultRetention,
    PromptCacheState promptCacheState) {

  public PromptCacheCapabilities {
    options = List.copyOf(options);
  }

  /** Whether the upstream manages the cache itself, so no cache intent is sent on the wire. */
  public boolean automaticCache() {
    return promptCacheState == PromptCacheState.PROVIDER_MANAGED;
  }

  public static PromptCacheCapabilities resolve(
      ProviderType protocol, String endpoint, String modelId) {
    List<PromptCacheOption> options = new ArrayList<>();
    PromptCacheState state =
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
              yield PromptCacheState.CONFIGURABLE;
            }
            if (official) {
              options.add(option(PromptCacheRetention.SHORT, null, null));
              if (OpenAiBackendCapabilities.supportsLegacyLongCache(modelId)) {
                options.add(option(PromptCacheRetention.LONG, 86400L, null));
              }
              yield PromptCacheState.CONFIGURABLE;
            }
            if (responses) {
              // Compatible Responses endpoints keep the existing Pi-aligned SHORT key and headers.
              options.add(option(PromptCacheRetention.SHORT, null, null));
              yield PromptCacheState.CONFIGURABLE;
            }
            // Compatible Chat endpoints expose no verifiable cache intent: Studio controls nothing.
            yield PromptCacheState.UNMANAGED;
          }
          case ANTHROPIC -> {
            if (isMiniMaxStable(endpoint, modelId)) {
              yield PromptCacheState.PROVIDER_MANAGED;
            }
            if (isOfficialAnthropic(endpoint)) {
              options.add(option(PromptCacheRetention.NONE, null, null));
              options.add(option(PromptCacheRetention.SHORT, 300L, 1.25));
              options.add(option(PromptCacheRetention.LONG, 3600L, 2.0));
              yield PromptCacheState.CONFIGURABLE;
            }
            // Preview and other compatible routes retain SHORT without a TTL guarantee.
            options.add(option(PromptCacheRetention.NONE, null, null));
            options.add(option(PromptCacheRetention.SHORT, null, null));
            yield PromptCacheState.CONFIGURABLE;
          }
          case GOOGLE -> PromptCacheState.PROVIDER_MANAGED;
        };
    // Without a selectable retention the persisted default stays NONE, matching the encoder's
    // silence.
    PromptCacheRetention defaultRetention =
        options.stream()
            .map(PromptCacheOption::retention)
            .filter(retention -> retention != PromptCacheRetention.NONE)
            .findFirst()
            .orElse(PromptCacheRetention.NONE);
    return new PromptCacheCapabilities(options, defaultRetention, state);
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
          && (host.equalsIgnoreCase("api.minimaxi.com")
              || host.equalsIgnoreCase("api.minimax.io")
              || host.equalsIgnoreCase("api.minimax.cn"));
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static boolean isMiniMaxStable(String endpoint, String modelId) {
    if (!isMiniMax(endpoint) || modelId == null) {
      return false;
    }
    return "MiniMax-M2.5".equalsIgnoreCase(modelId)
        || "MiniMax-M3.1-Flash".equalsIgnoreCase(modelId);
  }
}
