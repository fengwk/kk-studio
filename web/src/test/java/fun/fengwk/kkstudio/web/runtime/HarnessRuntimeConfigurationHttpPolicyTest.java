package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.retry.ModelHttpErrorPolicy;
import fun.fengwk.kkstudio.harness.runtime.retry.ModelHttpErrorPolicyProvider;
import fun.fengwk.kkstudio.platform.catalog.provider.configuration.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.platform.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 生产 HTTP 策略解析：Provider 覆盖优先于系统名单，省略继承、空数组禁用。 */
class HarnessRuntimeConfigurationHttpPolicyTest {

  private final AgentProviderConfigurationCodec codec =
      new AgentProviderConfigurationCodec(new ObjectMapper());
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

  private ModelHttpErrorPolicy policyFor(String providerName) {
    ModelHttpErrorPolicyProvider provider =
        new HarnessRuntimeConfiguration()
            .modelHttpErrorPolicyProvider(
                new SystemSettingsSnapshot(settingsWithRetryList(List.of(429))),
                new MapProviderRepository(providers),
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
            retryStatusCodes),
        SystemSettings.DEFAULT.environment(),
        SystemSettings.DEFAULT.network(),
        SystemSettings.DEFAULT.integrations(),
        SystemSettings.DEFAULT.storageMedia(),
        SystemSettings.DEFAULT.advanced());
  }

  private static AgentProvider provider(String name, String configJson) {
    AgentProvider provider = new AgentProvider();
    provider.setName(name);
    provider.setConfigJson(configJson);
    return provider;
  }

  /** 只读 Provider 仓库 fake：仅 {@link #getByName} 参与策略解析。 */
  private static final class MapProviderRepository implements AgentProviderRepository {

    private final Map<String, AgentProvider> byName;

    MapProviderRepository(Map<String, AgentProvider> byName) {
      this.byName = byName;
    }

    @Override
    public Page<AgentProvider> page(PageQuery pageQuery) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<AgentProvider> listAll() {
      throw new UnsupportedOperationException();
    }

    @Override
    public AgentProvider getByName(String name) {
      return byName.get(name);
    }

    @Override
    public AgentProvider getByNameForUpdate(String name) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean create(AgentProvider provider) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean updateByName(AgentProvider provider, long expectedVersion) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean deleteByName(String name, long expectedVersion) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean hasModels(String providerName) {
      throw new UnsupportedOperationException();
    }
  }
}
