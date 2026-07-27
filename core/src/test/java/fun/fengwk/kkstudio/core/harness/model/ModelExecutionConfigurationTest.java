package fun.fengwk.kkstudio.core.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import fun.fengwk.kkstudio.core.harness.model.provider.AnthropicProviderAdapter;
import fun.fengwk.kkstudio.core.harness.model.provider.GoogleProviderAdapter;
import fun.fengwk.kkstudio.core.harness.model.provider.OpenAiProviderAdapter;
import fun.fengwk.kkstudio.core.harness.model.provider.OpenAiResponsesProviderAdapter;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactories;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactory;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

/**
 * 验证 {@link ModelExecutionConfiguration} 暴露的 4 个 named ProviderFactory bean 全部被收集到 {@link
 * ProviderFactories}，避免 Spring 按返回类型匹配时丢失 bean。
 */
class ModelExecutionConfigurationTest extends PostgresSpringTestSupport {

  @Autowired
  @Qualifier("openaiProviderFactory")
  private ProviderFactory openaiProviderFactory;

  @Autowired
  @Qualifier("openaiResponsesProviderFactory")
  private ProviderFactory openaiResponsesProviderFactory;

  @Autowired
  @Qualifier("anthropicProviderFactory")
  private ProviderFactory anthropicProviderFactory;

  @Autowired
  @Qualifier("googleProviderFactory")
  private ProviderFactory googleProviderFactory;

  @Autowired private ProviderFactories providerFactories;

  @Test
  void registersAllFourNamedProviderFactoryBeans() {
    assertEquals(ProviderType.OPENAI, openaiProviderFactory.providerType());
    assertEquals(ProviderType.OPENAI_RESPONSES, openaiResponsesProviderFactory.providerType());
    assertEquals(ProviderType.ANTHROPIC, anthropicProviderFactory.providerType());
    assertEquals(ProviderType.GOOGLE, googleProviderFactory.providerType());
  }

  @Test
  void providerFactoriesLookupAllFourTypes() {
    assertNotNull(providerFactories.lookup(ProviderType.OPENAI).orElseThrow());
    assertNotNull(providerFactories.lookup(ProviderType.OPENAI_RESPONSES).orElseThrow());
    assertNotNull(providerFactories.lookup(ProviderType.ANTHROPIC).orElseThrow());
    assertNotNull(providerFactories.lookup(ProviderType.GOOGLE).orElseThrow());
    assertSame(openaiProviderFactory, providerFactories.lookup(ProviderType.OPENAI).orElseThrow());
    assertSame(
        openaiResponsesProviderFactory,
        providerFactories.lookup(ProviderType.OPENAI_RESPONSES).orElseThrow());
    assertSame(
        anthropicProviderFactory, providerFactories.lookup(ProviderType.ANTHROPIC).orElseThrow());
    assertSame(googleProviderFactory, providerFactories.lookup(ProviderType.GOOGLE).orElseThrow());
  }

  @Test
  void createsTheAdapterMatchingEachProviderType() {
    assertInstanceOf(OpenAiProviderAdapter.class, openaiProviderFactory.create("credential", "{}"));
    assertInstanceOf(
        OpenAiResponsesProviderAdapter.class,
        openaiResponsesProviderFactory.create("credential", "{}"));
    assertInstanceOf(
        AnthropicProviderAdapter.class, anthropicProviderFactory.create("credential", "{}"));
    assertInstanceOf(GoogleProviderAdapter.class, googleProviderFactory.create("credential", "{}"));
  }
}
