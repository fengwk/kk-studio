package fun.fengwk.kkstudio.core.ai.catalog.model.runtime;

import static fun.fengwk.kkstudio.core.ai.catalog.model.AgentModelTestData.executableConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.ai.catalog.model.repo.impl.mapper.AgentModelMapper;
import fun.fengwk.kkstudio.core.ai.catalog.model.repo.impl.model.AgentModelDO;

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
}
