package fun.fengwk.kkstudio.platform.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import fun.fengwk.kkstudio.harness.runtime.model.ImageInputTier;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactories;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactory;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMediaCapabilities;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayAffinity;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResourceBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.catalog.provider.configuration.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.platform.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobContent;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * {@link DatabaseProviderResolutionService} 的单元测试：不依赖 PostgreSQL，mock {@link
 * AgentProviderRepository} 并使用真实 {@link AgentProviderConfigurationCodec}，覆盖 resolve 全部分支——Provider
 * 行缺失 / 类型非法 / factory 未注册 / 空白 endpoint / adapter 构造失败，opener 的 ModelProvider 构造失败，以及规划期冻结的 cache
 * control 原样透传。
 */
class DatabaseProviderResolutionServiceTest {

  private static final String PROVIDER_NAME = "test-provider";
  private static final String ENDPOINT = "https://example/v1";
  private static final UUID GENERATION_ID = new UUID(0L, 1L);

  private final AgentProviderRepository repository = mock(AgentProviderRepository.class);
  private final AgentProviderConfigurationCodec configurationCodec =
      new AgentProviderConfigurationCodec(new ObjectMapper());

  // ---------- resolve 失败路径 ----------

  @Test
  void resolveRejectsNullFrozenFactsAndRequest() {
    DatabaseProviderResolutionService resolution = resolution();
    ProviderRequest request = request(ProviderCacheControl.none());
    assertThrows(
        NullPointerException.class, () -> resolution.resolve(null, GENERATION_ID, request));
    assertThrows(
        NullPointerException.class, () -> resolution.resolve(ProviderType.OPENAI, null, request));
    assertThrows(
        NullPointerException.class,
        () -> resolution.resolve(ProviderType.OPENAI, GENERATION_ID, null));
  }

  @Test
  void resolveRejectsMissingProviderRow() {
    when(repository.getByName(PROVIDER_NAME)).thenReturn(null);
    DatabaseProviderResolutionService resolution = resolution();
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                resolution.resolve(
                    ProviderType.OPENAI, GENERATION_ID, request(ProviderCacheControl.none())));
    assertEquals("provider not found: " + PROVIDER_NAME, failure.getMessage());
  }

  @Test
  void resolveRejectsNullProviderType() {
    when(repository.getByName(PROVIDER_NAME)).thenReturn(provider(null, ENDPOINT));
    DatabaseProviderResolutionService resolution = resolution();
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                resolution.resolve(
                    ProviderType.OPENAI, GENERATION_ID, request(ProviderCacheControl.none())));
    assertEquals("provider type must not be null", failure.getMessage());
  }

  @Test
  void resolveRejectsProviderWithNullConnectionGenerationId() {
    AgentProvider p = provider(ProviderType.OPENAI, ENDPOINT);
    p.setConnectionGenerationId(null);
    when(repository.getByName(PROVIDER_NAME)).thenReturn(p);
    ProviderFactory factory = openAiFactory(PromptCacheRetention.NONE);
    DatabaseProviderResolutionService resolution = resolution(factory);

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                resolution.resolve(
                    ProviderType.OPENAI, GENERATION_ID, request(ProviderCacheControl.none())));
    assertTrue(error.getMessage().contains("connectionGenerationId must not be null"));
  }

  @Test
  void resolveRejectsFrozenConnectionGenerationDrift() {
    AgentProvider provider = provider(ProviderType.OPENAI, ENDPOINT);
    provider.setConnectionGenerationId(new UUID(0L, 2L));
    when(repository.getByName(PROVIDER_NAME)).thenReturn(provider);
    ProviderAdapter adapter = adapter(ProviderType.OPENAI, mock(ModelProvider.class));
    ProviderFactory factory = factory(ProviderType.OPENAI, PromptCacheRetention.NONE, adapter);
    DatabaseProviderResolutionService resolution = resolution(factory);

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                resolution.resolve(
                    ProviderType.OPENAI, GENERATION_ID, request(ProviderCacheControl.none())));

    assertTrue(failure.getMessage().contains("provider connection generation drift"));
    // generation fence 必须先于 adapter 创建，确保漂移的 endpoint/credential 永远不会进入执行路径。
    verify(factory, never()).create(anyString(), anyString());
    verify(adapter, never()).create(any(ProviderDescriptor.class));
  }

  @Test
  void resolveRejectsFrozenProviderTypeDrift() {
    when(repository.getByName(PROVIDER_NAME))
        .thenReturn(provider(ProviderType.ANTHROPIC, ENDPOINT));
    DatabaseProviderResolutionService resolution =
        resolution(
            factory(
                ProviderType.ANTHROPIC,
                PromptCacheRetention.SHORT,
                adapter(ProviderType.ANTHROPIC, mock(ModelProvider.class))));
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                resolution.resolve(
                    ProviderType.OPENAI, GENERATION_ID, request(ProviderCacheControl.none())));
    assertEquals("provider type drift: frozen=OPENAI current=ANTHROPIC", failure.getMessage());
  }

  @Test
  void resolveRejectsUnregisteredFactoryType() {
    when(repository.getByName(PROVIDER_NAME)).thenReturn(provider(ProviderType.OPENAI, ENDPOINT));
    // 只注册了 ANTHROPIC factory，OPENAI 行没有对应 factory：resolve 必须确定性失败。
    DatabaseProviderResolutionService resolution =
        resolution(
            factory(
                ProviderType.ANTHROPIC,
                PromptCacheRetention.NONE,
                adapter(ProviderType.ANTHROPIC, mock(ModelProvider.class))));
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                resolution.resolve(
                    ProviderType.OPENAI, GENERATION_ID, request(ProviderCacheControl.none())));
    assertEquals("ProviderFactory is not registered for OPENAI", failure.getMessage());
  }

  @Test
  void resolveRejectsBlankBaseUrlAtAdmission() {
    when(repository.getByName(PROVIDER_NAME)).thenReturn(provider(ProviderType.OPENAI, "  "));
    DatabaseProviderResolutionService resolution =
        resolution(openAiFactory(PromptCacheRetention.SHORT));
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                resolution.resolve(
                    ProviderType.OPENAI, GENERATION_ID, request(ProviderCacheControl.none())));
    assertEquals("endpoint must not be blank", failure.getMessage());
  }

  @Test
  void resolveWrapsPersistedConfigurationFailureWithoutUntrustedCause() {
    // 意图：损坏的持久 Provider 配置可能含敏感内容；解析边界只暴露稳定定位信息，不保留原始
    // Jackson cause 或配置片段。
    AgentProvider p = provider(ProviderType.OPENAI, ENDPOINT);
    p.setConfigJson("{\"credential\":\"sensitive-value\"");
    when(repository.getByName(PROVIDER_NAME)).thenReturn(p);
    DatabaseProviderResolutionService resolution =
        resolution(openAiFactory(PromptCacheRetention.NONE));

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                resolution.resolve(
                    ProviderType.OPENAI, GENERATION_ID, request(ProviderCacheControl.none())));

    assertEquals("cannot parse provider configuration for " + PROVIDER_NAME, failure.getMessage());
    assertFalse(failure.getMessage().contains("sensitive-value"));
    assertNull(failure.getCause());
  }

  @Test
  void resolveWrapsFactoryCreateFailure() {
    when(repository.getByName(PROVIDER_NAME)).thenReturn(provider(ProviderType.OPENAI, ENDPOINT));
    ProviderFactory factory = factory(ProviderType.OPENAI, PromptCacheRetention.SHORT, null);
    when(factory.create(anyString(), anyString()))
        .thenThrow(new IllegalStateException("factory boom"));
    DatabaseProviderResolutionService resolution = resolution(factory);
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                resolution.resolve(
                    ProviderType.OPENAI, GENERATION_ID, request(ProviderCacheControl.none())));
    assertEquals("cannot create provider adapter for " + PROVIDER_NAME, failure.getMessage());
    assertNull(failure.getCause());
  }

  @Test
  void resolveRejectsNullAdapterFromFactory() {
    when(repository.getByName(PROVIDER_NAME)).thenReturn(provider(ProviderType.OPENAI, ENDPOINT));
    DatabaseProviderResolutionService resolution =
        resolution(factory(ProviderType.OPENAI, PromptCacheRetention.SHORT, null));
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                resolution.resolve(
                    ProviderType.OPENAI, GENERATION_ID, request(ProviderCacheControl.none())));
    assertEquals(
        "ProviderFactory returned null adapter for " + PROVIDER_NAME, failure.getMessage());
  }

  @Test
  void resolveRejectsAdapterTypeMismatch() {
    when(repository.getByName(PROVIDER_NAME)).thenReturn(provider(ProviderType.OPENAI, ENDPOINT));
    // OPENAI factory 返回 GOOGLE adapter：类型不一致必须确定性失败，不能静默使用错误协议。
    DatabaseProviderResolutionService resolution =
        resolution(
            factory(
                ProviderType.OPENAI,
                PromptCacheRetention.SHORT,
                adapter(ProviderType.GOOGLE, mock(ModelProvider.class))));
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                resolution.resolve(
                    ProviderType.OPENAI, GENERATION_ID, request(ProviderCacheControl.none())));
    assertEquals("ProviderFactory returned adapter type GOOGLE for OPENAI", failure.getMessage());
  }

  // ---------- resolve 成功路径与 opener ----------

  @Test
  void resolveAcceptsAllCatalogProviderTypes() {
    // 四个 ProviderType 都必须直接从当前 catalog 领域对象解析并成功 open。
    for (ProviderType providerType : ProviderType.values()) {
      when(repository.getByName(PROVIDER_NAME)).thenReturn(provider(providerType, ENDPOINT));
      DatabaseProviderResolutionService resolution =
          resolution(
              factory(
                  providerType,
                  PromptCacheRetention.SHORT,
                  adapter(providerType, mock(ModelProvider.class))));
      resolution
          .resolve(providerType, GENERATION_ID, request(ProviderCacheControl.none()))
          .openProvider(ModelCallTimeoutPolicy.DEFAULT);
    }
  }

  @Test
  void resolveCarriesCurrentRowFactsIntoEffectiveRequestAndOpener() {
    when(repository.getByName(PROVIDER_NAME)).thenReturn(provider(ProviderType.OPENAI, ENDPOINT));
    ModelProvider modelProvider = mock(ModelProvider.class);
    ProviderAdapter adapter = adapter(ProviderType.OPENAI, modelProvider);
    DatabaseProviderResolutionService resolution =
        resolution(factory(ProviderType.OPENAI, PromptCacheRetention.SHORT, adapter));

    ProviderRequest persisted =
        request(ProviderCacheControl.session(PromptCacheRetention.SHORT, "pc-key"));
    ProviderResolutionService.ResolvedExecution resolved =
        resolution.resolve(ProviderType.OPENAI, GENERATION_ID, persisted);

    // 有效 request 与持久 request 共享模型 / variant / 消息 / tools 与规划期冻结的 cache control。
    assertEquals(persisted.model(), resolved.effectiveRequest().model());
    assertEquals(persisted.variant(), resolved.effectiveRequest().variant());
    assertEquals(persisted.messages(), resolved.effectiveRequest().messages());
    assertEquals(persisted.tools(), resolved.effectiveRequest().tools());
    assertEquals(
        ProviderCacheControl.session(PromptCacheRetention.SHORT, "pc-key"),
        resolved.effectiveRequest().cacheControl());
    assertEquals(ModelCallTimeoutPolicy.DEFAULT, resolved.timeoutPolicy(), "config 未配置超时字段时使用默认策略");

    // opener 用冻结的连接事实构造 descriptor，并返回 adapter 创建的 ModelProvider。
    assertSame(modelProvider, resolved.openProvider(resolved.timeoutPolicy()));
    verify(adapter)
        .create(
            argThat(
                descriptor ->
                    descriptor.providerName().equals(PROVIDER_NAME)
                        && descriptor.type() == ProviderType.OPENAI
                        && descriptor.endpoint().equals(ENDPOINT)
                        && descriptor
                            .modelCallTimeoutPolicy()
                            .equals(ModelCallTimeoutPolicy.DEFAULT)));
  }

  @Test
  void resolveMaterializesNestedToolResultResourcesInEffectiveRequest() {
    when(repository.getByName(PROVIDER_NAME)).thenReturn(provider(ProviderType.OPENAI, ENDPOINT));
    DatabaseProviderResolutionService resolution =
        resolution(
            factory(
                ProviderType.OPENAI,
                PromptCacheRetention.SHORT,
                adapter(ProviderType.OPENAI, mock(ModelProvider.class))));
    ProviderTextBlock sibling = new ProviderTextBlock("before");
    ProviderToolResultBlock persistedResult =
        new ProviderToolResultBlock(
            "call-1",
            "read",
            List.of(
                sibling, new ProviderResourceBlock(new UUID(0L, 1L), "scan.txt", "tiny preview")),
            false,
            "{\"bytes\":42}");
    ProviderRequest persisted =
        request(
            ProviderCacheControl.none(),
            List.of(new ProviderMessage(ProviderMessageRole.TOOL, List.of(persistedResult))),
            List.of());

    // 意图：验证解析服务交给后续 Provider transport 的 effectiveRequest 已移除嵌套 durable Resource。
    ProviderResolutionService.ResolvedExecution resolved =
        resolution.resolve(ProviderType.OPENAI, GENERATION_ID, persisted);

    ProviderToolResultBlock effectiveResult =
        assertInstanceOf(
            ProviderToolResultBlock.class,
            resolved.effectiveRequest().messages().get(0).contents().get(0));
    assertEquals("call-1", effectiveResult.toolCallId());
    assertEquals("read", effectiveResult.toolName());
    assertSame(sibling, effectiveResult.contents().get(0));
    assertInstanceOf(ProviderTextBlock.class, effectiveResult.contents().get(1));
    assertTrue(
        effectiveResult.contents().stream().noneMatch(ProviderResourceBlock.class::isInstance),
        "effectiveRequest must not expose nested ProviderResourceBlock to the adapter");
  }

  /**
   * 意图：resolve 交给 transport 的 effectiveRequest 必须原样保留 assistant 的 native replay state（同一实例），
   * 即使同时存在需要物化的用户 Resource——物化边界不得解析或重建 replay payload。
   */
  @ParameterizedTest
  @EnumSource(ProviderReplayFormat.class)
  void resolvePreservesAssistantReplayStateThroughResourceMaterialization(
      ProviderReplayFormat format) {
    when(repository.getByName(PROVIDER_NAME)).thenReturn(provider(ProviderType.OPENAI, ENDPOINT));
    DatabaseProviderResolutionService resolution =
        resolution(
            factory(
                ProviderType.OPENAI,
                PromptCacheRetention.SHORT,
                adapter(ProviderType.OPENAI, mock(ModelProvider.class))));
    ProviderReplayState replayState = sampleReplayState(format);
    ProviderMessage assistant =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderTextBlock("answer"), new ProviderThinkingBlock("reasoning")),
            replayState);
    ProviderRequest persisted =
        request(
            ProviderCacheControl.none(),
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER,
                    List.of(new ProviderResourceBlock(new UUID(0L, 1L), "scan.txt", "tiny"))),
                assistant),
            List.of());

    ProviderResolutionService.ResolvedExecution resolved =
        resolution.resolve(ProviderType.OPENAI, GENERATION_ID, persisted);

    List<ProviderMessage> effective = resolved.effectiveRequest().messages();
    assertEquals(2, effective.size());
    assertEquals(ProviderMessageRole.USER, effective.get(0).role());
    assertTrue(
        effective.get(0).contents().stream().noneMatch(ProviderResourceBlock.class::isInstance),
        "Resource 必须已被物化");
    assertEquals(ProviderMessageRole.ASSISTANT, effective.get(1).role());
    assertSame(replayState, effective.get(1).replayState(), "replay state 必须是同一实例");
    assertEquals(format, effective.get(1).replayState().format());
    assertEquals(
        replayState.payload(),
        effective.get(1).replayState().payload(),
        "不同 Provider 的 replay payload 必须逐字段保持不变（本边界不得解析或重写 payload）");
    assertEquals(replayState.affinity(), effective.get(1).replayState().affinity());
    assertSame(
        assistant.contents().get(1),
        effective.get(1).contents().get(1),
        "durable thinking 必须保持 identity，绝不重建");
  }

  /** 合成 replay state：payload 刻意不透明，只为验证 resolve 边界的对象透传。 */
  private static ProviderReplayState sampleReplayState(ProviderReplayFormat format) {
    try {
      return new ProviderReplayState(
          format,
          new ProviderReplayAffinity(ProviderType.OPENAI, PROVIDER_NAME, GENERATION_ID, "model"),
          new ObjectMapper()
              .readTree("{\"output\":[{\"type\":\"opaque-" + format.name() + "\"}]}"));
    } catch (JsonProcessingException error) {
      throw new IllegalStateException(error);
    }
  }

  @Test
  void openProviderRejectsNullModelProvider() {
    when(repository.getByName(PROVIDER_NAME)).thenReturn(provider(ProviderType.OPENAI, ENDPOINT));
    DatabaseProviderResolutionService resolution =
        resolution(
            factory(
                ProviderType.OPENAI,
                PromptCacheRetention.SHORT,
                adapter(ProviderType.OPENAI, null)));
    ProviderResolutionService.ResolvedExecution resolved =
        resolution.resolve(
            ProviderType.OPENAI, GENERATION_ID, request(ProviderCacheControl.none()));
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class, () -> resolved.openProvider(resolved.timeoutPolicy()));
    assertEquals(
        "cannot create ModelProvider for " + PROVIDER_NAME + ": null provider",
        failure.getMessage());
  }

  @Test
  void openProviderWrapsAdapterCreateFailure() {
    when(repository.getByName(PROVIDER_NAME)).thenReturn(provider(ProviderType.OPENAI, ENDPOINT));
    ProviderAdapter adapter = adapter(ProviderType.OPENAI, mock(ModelProvider.class));
    when(adapter.create(any(ProviderDescriptor.class)))
        .thenThrow(new IllegalStateException("adapter boom"));
    DatabaseProviderResolutionService resolution =
        resolution(factory(ProviderType.OPENAI, PromptCacheRetention.SHORT, adapter));
    ProviderResolutionService.ResolvedExecution resolved =
        resolution.resolve(
            ProviderType.OPENAI, GENERATION_ID, request(ProviderCacheControl.none()));
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class, () -> resolved.openProvider(resolved.timeoutPolicy()));
    assertEquals("cannot create ModelProvider for " + PROVIDER_NAME, failure.getMessage());
    assertNull(failure.getCause());
  }

  @Test
  void openProviderWrapsIllegalArgumentExceptionWithoutMessage() {
    when(repository.getByName(PROVIDER_NAME)).thenReturn(provider(ProviderType.OPENAI, ENDPOINT));
    ProviderAdapter adapter = adapter(ProviderType.OPENAI, mock(ModelProvider.class));
    when(adapter.create(any(ProviderDescriptor.class))).thenThrow(new IllegalArgumentException());
    DatabaseProviderResolutionService resolution =
        resolution(factory(ProviderType.OPENAI, PromptCacheRetention.SHORT, adapter));
    ProviderResolutionService.ResolvedExecution resolved =
        resolution.resolve(
            ProviderType.OPENAI, GENERATION_ID, request(ProviderCacheControl.none()));
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class, () -> resolved.openProvider(resolved.timeoutPolicy()));
    assertEquals("cannot create ModelProvider for " + PROVIDER_NAME, failure.getMessage());
    assertNull(failure.getCause());
  }

  @Test
  void openProviderWrapsIllegalArgumentExceptionWithUnrelatedMessage() {
    when(repository.getByName(PROVIDER_NAME)).thenReturn(provider(ProviderType.OPENAI, ENDPOINT));
    ProviderAdapter adapter = adapter(ProviderType.OPENAI, mock(ModelProvider.class));
    // 任意 adapter 异常都必须转换为无 cause 的稳定错误，不能透传实现细节。
    when(adapter.create(any(ProviderDescriptor.class)))
        .thenThrow(new IllegalArgumentException("broken adapter"));
    DatabaseProviderResolutionService resolution =
        resolution(factory(ProviderType.OPENAI, PromptCacheRetention.SHORT, adapter));
    ProviderResolutionService.ResolvedExecution resolved =
        resolution.resolve(
            ProviderType.OPENAI, GENERATION_ID, request(ProviderCacheControl.none()));
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class, () -> resolved.openProvider(resolved.timeoutPolicy()));
    assertEquals("cannot create ModelProvider for " + PROVIDER_NAME, failure.getMessage());
    assertNull(failure.getCause());
  }

  @Test
  void openProviderDoesNotTrustPrefixedAdapterError() {
    when(repository.getByName(PROVIDER_NAME)).thenReturn(provider(ProviderType.OPENAI, ENDPOINT));
    ProviderAdapter adapter = adapter(ProviderType.OPENAI, mock(ModelProvider.class));
    // 不可信 adapter 即使伪造约定前缀，也不能绕过脱敏边界。
    IllegalArgumentException original =
        new IllegalArgumentException("cannot create ModelProvider for x: sensitive-value");
    when(adapter.create(any(ProviderDescriptor.class))).thenThrow(original);
    DatabaseProviderResolutionService resolution =
        resolution(factory(ProviderType.OPENAI, PromptCacheRetention.SHORT, adapter));
    ProviderResolutionService.ResolvedExecution resolved =
        resolution.resolve(
            ProviderType.OPENAI, GENERATION_ID, request(ProviderCacheControl.none()));
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class, () -> resolved.openProvider(resolved.timeoutPolicy()));
    assertEquals("cannot create ModelProvider for " + PROVIDER_NAME, failure.getMessage());
    assertFalse(failure.getMessage().contains("sensitive-value"));
    assertNull(failure.getCause());
  }

  // ---------- 冻结 cache control 透传 ----------

  @Test
  void resolveKeepsNoneCacheControlUnchanged() {
    when(repository.getByName(PROVIDER_NAME)).thenReturn(provider(ProviderType.OPENAI, ENDPOINT));
    DatabaseProviderResolutionService resolution =
        resolution(
            factory(
                ProviderType.OPENAI,
                PromptCacheRetention.SHORT,
                adapter(ProviderType.OPENAI, mock(ModelProvider.class))));
    ProviderResolutionService.ResolvedExecution resolved =
        resolution.resolve(
            ProviderType.OPENAI, GENERATION_ID, request(ProviderCacheControl.none()));
    assertEquals(ProviderCacheControl.none(), resolved.effectiveRequest().cacheControl());
  }

  /** 意图：cache control 在规划期冻结（retention + session key），resolve 只原样透传，绝不按当前配置重新规范化。 */
  @Test
  void resolvePassesFrozenCacheControlThroughUnchanged() {
    when(repository.getByName(PROVIDER_NAME)).thenReturn(provider(ProviderType.OPENAI, ENDPOINT));
    DatabaseProviderResolutionService resolution =
        resolution(
            factory(
                ProviderType.OPENAI,
                PromptCacheRetention.SHORT,
                adapter(ProviderType.OPENAI, mock(ModelProvider.class))));
    ProviderCacheControl frozen =
        ProviderCacheControl.session(PromptCacheRetention.LONG, "session-key");

    ProviderResolutionService.ResolvedExecution resolved =
        resolution.resolve(ProviderType.OPENAI, GENERATION_ID, request(frozen));

    assertSame(frozen, resolved.effectiveRequest().cacheControl());
  }

  // ---------- adapter 内联媒体能力 ----------

  /** 意图：resolve 必须把当前 adapter 声明的内联媒体能力传入物化边界，让模型模态与位置能力共同决定是否内联；否则资源 只能在文本回退与媒体块之间做出与协议无关的错误选择。 */
  @Test
  void resolveMaterializesUserResourceUsingAdapterMediaCapabilities() {
    when(repository.getByName(PROVIDER_NAME)).thenReturn(provider(ProviderType.OPENAI, ENDPOINT));
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    StorageBlobContentService contentService = mock(StorageBlobContentService.class);
    UUID blobId = new UUID(0L, 1L);
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setMediaType("image/png");
    blob.setSizeBytes(3L);
    blob.setState(StorageBlobState.ACTIVE);
    when(blobManager.getBlob(blobId)).thenReturn(blob);
    when(contentService.readBlobContent(
            blobId, ProviderInlineBlobReader.Limits.DEFAULT.maxBlobBytes()))
        .thenReturn(new StorageBlobContent(blobId, new byte[] {0, 1, 2}, "image/png", 3L));
    ProviderMediaCapabilities capabilities =
        new ProviderMediaCapabilities(
            Set.of(ModelInputModality.IMAGE), Set.of(ModelInputModality.IMAGE));
    ProviderAdapter adapter = adapter(ProviderType.OPENAI, mock(ModelProvider.class), capabilities);
    DatabaseProviderResolutionService resolution =
        resolution(
            blobManager,
            contentService,
            new ProviderFactories(
                List.of(factory(ProviderType.OPENAI, PromptCacheRetention.SHORT, adapter))));
    ProviderRequest persisted =
        request(
            ProviderCacheControl.none(),
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER,
                    List.of(
                        ProviderResourceBlock.media(
                            blobId, "scan.png", "tiny", ImageInputTier.ORIGINAL)))),
            List.of(),
            Set.of(ModelInputModality.IMAGE));

    ProviderResolutionService.ResolvedExecution resolved =
        resolution.resolve(ProviderType.OPENAI, GENERATION_ID, persisted);

    ProviderImageBlock image =
        assertInstanceOf(
            ProviderImageBlock.class,
            resolved.effectiveRequest().messages().get(0).contents().get(0));
    assertEquals("data:image/png;base64,AAEC", image.source());
  }

  /** 意图：adapter 未声明能力时图片不得被降级成文本描述，必须在读取任何存储内容之前显式失败。 */
  @Test
  void resolveFailsExplicitlyWhenAdapterDeclaresNoMediaCapabilityForImage() {
    when(repository.getByName(PROVIDER_NAME)).thenReturn(provider(ProviderType.OPENAI, ENDPOINT));
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    StorageBlobContentService contentService = mock(StorageBlobContentService.class);
    UUID blobId = new UUID(0L, 1L);
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setMediaType("image/png");
    blob.setSizeBytes(3L);
    blob.setState(StorageBlobState.ACTIVE);
    when(blobManager.getBlob(blobId)).thenReturn(blob);
    DatabaseProviderResolutionService resolution =
        resolution(
            blobManager,
            contentService,
            new ProviderFactories(
                List.of(
                    factory(
                        ProviderType.OPENAI,
                        PromptCacheRetention.SHORT,
                        adapter(ProviderType.OPENAI, mock(ModelProvider.class))))));
    ProviderRequest persisted =
        request(
            ProviderCacheControl.none(),
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER,
                    List.of(
                        ProviderResourceBlock.media(
                            blobId, "scan.png", "tiny", ImageInputTier.ORIGINAL)))),
            List.of(),
            Set.of(ModelInputModality.IMAGE));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> resolution.resolve(ProviderType.OPENAI, GENERATION_ID, persisted));

    assertTrue(error.getMessage().contains("cannot be sent"), error.getMessage());
    verify(contentService, never()).readBlobContent(any(), anyLong());
    verify(blobManager, never()).presignOriginalUrl(any());
  }

  /**
   * 意图：media 投影只由冻结请求的模型模态决定；冻结模型未声明 IMAGE 时，即使当前 adapter 声明了 USER IMAGE 能力也不得内联，
   * 必须在读取任何存储内容之前显式失败（不使用任何实时模型能力）。
   */
  @Test
  void resolveGatesMediaOnFrozenModelModalitiesNotAdapterCapabilityAlone() {
    when(repository.getByName(PROVIDER_NAME)).thenReturn(provider(ProviderType.OPENAI, ENDPOINT));
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    StorageBlobContentService contentService = mock(StorageBlobContentService.class);
    UUID blobId = new UUID(0L, 1L);
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setMediaType("image/png");
    blob.setSizeBytes(3L);
    blob.setState(StorageBlobState.ACTIVE);
    when(blobManager.getBlob(blobId)).thenReturn(blob);
    ProviderMediaCapabilities capabilities =
        new ProviderMediaCapabilities(
            Set.of(ModelInputModality.IMAGE), Set.of(ModelInputModality.IMAGE));
    ProviderAdapter adapter = adapter(ProviderType.OPENAI, mock(ModelProvider.class), capabilities);
    DatabaseProviderResolutionService resolution =
        resolution(
            blobManager,
            contentService,
            new ProviderFactories(
                List.of(factory(ProviderType.OPENAI, PromptCacheRetention.SHORT, adapter))));
    ProviderRequest persisted =
        request(
            ProviderCacheControl.none(),
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER,
                    List.of(
                        ProviderResourceBlock.media(
                            blobId, "scan.png", "tiny", ImageInputTier.ORIGINAL)))),
            List.of(),
            Set.of(ModelInputModality.TEXT));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> resolution.resolve(ProviderType.OPENAI, GENERATION_ID, persisted));

    assertTrue(error.getMessage().contains("model does not declare IMAGE"), error.getMessage());
    verify(contentService, never()).readBlobContent(any(), anyLong());
  }

  // ---------- 测试基座 ----------

  private DatabaseProviderResolutionService resolution(ProviderFactory... factories) {
    return resolution(
        mock(StorageBlobManager.class),
        mock(StorageBlobContentService.class),
        new ProviderFactories(List.of(factories)));
  }

  private DatabaseProviderResolutionService resolution(
      StorageBlobManager blobManager,
      StorageBlobContentService contentService,
      ProviderFactories factories) {
    return new DatabaseProviderResolutionService(
        repository,
        configurationCodec,
        factories,
        new ProviderResourceMaterializer(blobManager, contentService));
  }

  private static ProviderFactory openAiFactory(PromptCacheRetention retention) {
    return factory(
        ProviderType.OPENAI, retention, adapter(ProviderType.OPENAI, mock(ModelProvider.class)));
  }

  private static ProviderFactory factory(
      ProviderType type, PromptCacheRetention retention, ProviderAdapter adapter) {
    ProviderFactory factory = mock(ProviderFactory.class);
    when(factory.providerType()).thenReturn(type);
    when(factory.promptCacheRetention()).thenReturn(retention);
    when(factory.promptCacheRetention(any())).thenReturn(retention);
    when(factory.create(anyString(), anyString())).thenReturn(adapter);
    return factory;
  }

  private static ProviderAdapter adapter(ProviderType type, ModelProvider modelProvider) {
    return adapter(type, modelProvider, ProviderMediaCapabilities.NONE);
  }

  /**
   * 测试意图：显式声明 adapter 的内联媒体能力，避免 mock 默认返回 {@link ProviderMediaCapabilities#NONE}
   * 造成物化路径静默退化为文本，从而掩盖真实断言。
   */
  private static ProviderAdapter adapter(
      ProviderType type, ModelProvider modelProvider, ProviderMediaCapabilities mediaCapabilities) {
    ProviderAdapter adapter = mock(ProviderAdapter.class);
    when(adapter.providerType()).thenReturn(type);
    when(adapter.mediaCapabilities()).thenReturn(mediaCapabilities);
    when(adapter.create(any(ProviderDescriptor.class))).thenReturn(modelProvider);
    return adapter;
  }

  private static AgentProvider provider(ProviderType type, String baseUrl) {
    AgentProvider provider = new AgentProvider();
    provider.setName(PROVIDER_NAME);
    provider.setProviderType(type);
    provider.setBaseUrl(baseUrl);
    provider.setCredential("secret");
    provider.setConfigJson("{}");
    provider.setConnectionGenerationId(GENERATION_ID);
    return provider;
  }

  private static ProviderRequest request(ProviderCacheControl cacheControl) {
    return request(cacheControl, List.of(), List.of());
  }

  private static ProviderRequest request(
      ProviderCacheControl cacheControl,
      List<ProviderMessage> messages,
      List<ProviderToolDefinition> tools) {
    return request(cacheControl, messages, tools, Set.of(ModelInputModality.TEXT));
  }

  private static ProviderRequest request(
      ProviderCacheControl cacheControl,
      List<ProviderMessage> messages,
      List<ProviderToolDefinition> tools,
      Set<ModelInputModality> inputModalities) {
    return new ProviderRequest(
        new ModelDescriptor(PROVIDER_NAME, "model", "model", inputModalities, false, false),
        new ModelVariant("default"),
        1024,
        "Test system instruction.",
        messages,
        tools,
        cacheControl);
  }
}
