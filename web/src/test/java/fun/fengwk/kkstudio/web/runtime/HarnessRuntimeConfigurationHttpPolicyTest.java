package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.api.retry.ModelHttpErrorPolicy;
import fun.fengwk.kkstudio.platform.catalog.provider.Provider;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderConfigurationCodec;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderRepository;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsTestFixture;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 生产 HTTP 策略解析：Provider 覆盖优先于系统名单，省略继承、空数组禁用。 */
class HarnessRuntimeConfigurationHttpPolicyTest {

  private final ProviderConfigurationCodec codec =
      new ProviderConfigurationCodec(JsonMapper.builder().build());
  private final Map<String, Provider> providers = new HashMap<>();

  @Test
  void providerOverrideTakesPriorityOverSystemList() {
    providers.put("p-override", provider("p-override", "{\"modelHttpRetryStatusCodes\":[500]}"));
    ModelHttpErrorPolicy policy = policyFor("p-override");

    assertTrue(policy.allowsRetry(500));
    assertFalse(policy.allowsRetry(429));
  }

  @Test
  void missingOverrideInheritsSystemList() {
    providers.put("p-inherit", provider("p-inherit", "{}"));
    ModelHttpErrorPolicy policy = policyFor("p-inherit");

    assertTrue(policy.allowsRetry(429));
    assertFalse(policy.allowsRetry(500));
  }

  @Test
  void emptyOverrideDisablesHttpRetry() {
    providers.put("p-disable", provider("p-disable", "{\"modelHttpRetryStatusCodes\":[]}"));
    ModelHttpErrorPolicy policy = policyFor("p-disable");

    assertFalse(policy.allowsRetry(429));
    assertFalse(policy.allowsRetry(500));
  }

  @Test
  void unknownProviderFallsBackToSystemList() {
    assertTrue(policyFor("missing").allowsRetry(429));
  }

  /** live 路径绝不允许 null/blank 冻结身份；未知名称才按“继承系统名单”处理。 */
  @Test
  void nullOrBlankProviderNameMustNotEnterLivePath() {
    assertThrows(NullPointerException.class, () -> policyFor(null));
    assertThrows(IllegalArgumentException.class, () -> policyFor("  "));
  }

  /** 只读 Provider 仓库替身：仅 getByName 参与策略解析。 */
  private ProviderRepository repository() {
    ProviderRepository repository = mock(ProviderRepository.class);
    when(repository.getByName(anyString()))
        .thenAnswer(invocation -> providers.get(invocation.<String>getArgument(0)));
    return repository;
  }

  private ModelHttpErrorPolicy policyFor(String providerName) {
    Function<String, ModelHttpErrorPolicy> provider =
        new HarnessProcessorConfiguration()
            .modelHttpErrorPolicyProvider(
                new SystemSettingsSnapshot(settingsWithRetryList(List.of(429))),
                repository(),
                codec);
    return provider.apply(providerName);
  }

  private static SystemSettings settingsWithRetryList(List<Integer> retryStatusCodes) {
    SystemSettings.AiRuntime ai = SystemSettingsTestFixture.DEFAULT.aiRuntime();
    return new SystemSettings(
        SystemSettingsTestFixture.DEFAULT.tool(),
        new SystemSettings.AiRuntime(
            ai.retryMaxRetries(),
            ai.retryBackoffStrategy(),
            ai.retryBaseDelayMillis(),
            ai.retryMaxDelayMillis(),
            ai.compactionModel(),
            ai.compactionKeepRecentTokens(),
            ai.subagentMaxDepth(),
            ai.subagentMaxConcurrency(),
            ai.subagentMaxTotalConcurrency(),
            ai.subagentMaxTurns(),
            retryStatusCodes),
        SystemSettingsTestFixture.DEFAULT.environment(),
        SystemSettingsTestFixture.DEFAULT.backendNetwork(),
        SystemSettingsTestFixture.DEFAULT.storageMedia(),
        SystemSettingsTestFixture.DEFAULT.advanced());
  }

  private static Provider provider(String name, String configJson) {
    return Provider.builder().name(name).configJson(configJson).build();
  }
}
