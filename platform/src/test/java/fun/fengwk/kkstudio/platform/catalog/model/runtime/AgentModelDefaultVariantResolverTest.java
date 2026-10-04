package fun.fengwk.kkstudio.platform.catalog.model.runtime;

import static fun.fengwk.kkstudio.platform.catalog.model.AgentModelTestData.executableConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.catalog.model.repo.impl.mapper.AgentModelMapper;
import fun.fengwk.kkstudio.platform.catalog.model.repo.impl.model.AgentModelDO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;

/** Model Variant 覆盖必须引用当前的复合 Model 标识。 */
class AgentModelDefaultVariantResolverTest {

  @Test
  void resolvesDefaultAndRejectsUnknownModelOrVariant() {
    AgentModelMapper mapper = mock(AgentModelMapper.class);
    AgentModelRuntimeConfigParser parser = new AgentModelRuntimeConfigParser(new ObjectMapper());
    AgentModelDO model = new AgentModelDO();
    model.setProviderName("provider");
    model.setName("model");
    model.setConfigJson(parser.encode(executableConfig()));
    when(mapper.getByProviderNameAndName("provider", "model")).thenReturn(model);

    AgentModelDefaultVariantResolver resolver =
        new AgentModelDefaultVariantResolver(mapper, parser);

    assertEquals("default", resolver.resolve("provider", "model", null));
    assertEquals("default", resolver.resolve("provider", "model", " default "));
    assertEquals(
        "unknown agent model variant: model=provider/model, variant=missing",
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolve("provider", "model", "missing"))
            .getMessage());
    assertEquals(
        "unknown agent model: missing/model",
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolve("missing", "model", "default"))
            .getMessage());
  }

  /** 纯入口只依赖传入 config：override 归一化、default 回退、未声明 variant 与缺失 default 的错误语义保持。 */
  @Test
  void resolvesConfiguredVariantAgainstProvidedConfig() {
    AgentModelConfigDTO config = executableConfig();

    assertEquals(
        "default",
        AgentModelDefaultVariantResolver.resolveConfiguredVariant(
            "provider", "model", null, config));
    assertEquals(
        "default",
        AgentModelDefaultVariantResolver.resolveConfiguredVariant(
            "provider", "model", "  default  ", config));
    assertEquals(
        "unknown agent model variant: model=provider/model, variant=missing",
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    AgentModelDefaultVariantResolver.resolveConfiguredVariant(
                        "provider", "model", "missing", config))
            .getMessage());
    AgentModelConfigDTO noDefault = executableConfig();
    noDefault.setDefaultVariant(null);
    assertEquals(
        "model defaultVariant missing: provider/model",
        assertThrows(
                IllegalStateException.class,
                () ->
                    AgentModelDefaultVariantResolver.resolveConfiguredVariant(
                        "provider", "model", " ", noDefault))
            .getMessage());
  }
}
