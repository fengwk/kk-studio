package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.runtime.retry.ModelHttpErrorPolicy;
import fun.fengwk.kkstudio.harness.runtime.retry.ModelHttpErrorPolicyProvider;
import fun.fengwk.kkstudio.platform.catalog.provider.AgentProvider;
import fun.fengwk.kkstudio.platform.catalog.provider.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.platform.catalog.provider.AgentProviderRepository;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 生产 HTTP 策略解析：Provider 覆盖优先于系统名单，省略继承、空数组禁用。 */
class HarnessRuntimeConfigurationHttpPolicyTest {

  private final AgentProviderConfigurationCodec codec =
      new AgentProviderConfigurationCodec(JsonMapper.builder().build());
  private final Map<String, AgentProvider> providers = new HashMap<>();

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
  private AgentProviderRepository repository() {
    AgentProviderRepository repository = mock(AgentProviderRepository.class);
    when(repository.getByName(anyString()))
        .thenAnswer(invocation -> providers.get(invocation.<String>getArgument(0)));
    return repository;
  }

  private ModelHttpErrorPolicy policyFor(String providerName) {
    ModelHttpErrorPolicyProvider provider =
        new HarnessRuntimeConfiguration()
            .modelHttpErrorPolicyProvider(
                new SystemSettingsSnapshot(settingsWithRetryList(List.of(429))),
                repository(),
                codec);
    return provider.policy(providerName);
  }

  private static SystemSettings settingsWithRetryList(List<Integer> retryStatusCodes) {
    SystemSettings.AiRuntime ai = SystemSettings.DEFAULT.aiRuntime();
    return new SystemSettings(
        SystemSettings.DEFAULT.tool(),
        new SystemSettings.AiRuntime(
            ai.retryMaxRetries(),
            ai.retryBackoffStrategy(),
            ai.retryBaseDelayMillis(),
            ai.retryMaxDelayMillis(),
            ai.compactionKeepRecentTokens(),
            ai.subagentMaxDepth(),
            ai.subagentMaxConcurrency(),
            ai.subagentMaxTotalConcurrency(),
            ai.subagentMaxTurns(),
            retryStatusCodes,
            ai.modelCatalogSource()),
        SystemSettings.DEFAULT.environment(),
        SystemSettings.DEFAULT.network(),
        SystemSettings.DEFAULT.storageMedia(),
        SystemSettings.DEFAULT.advanced());
  }

  private static AgentProvider provider(String name, String configJson) {
    return AgentProvider.builder().name(name).configJson(configJson).build();
  }
}
