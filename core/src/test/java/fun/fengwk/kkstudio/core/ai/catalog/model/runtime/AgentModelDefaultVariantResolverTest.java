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

/** Model Variant overrides must reference the current Model configuration. */
class AgentModelDefaultVariantResolverTest {

  @Test
  void resolvesDefaultAndRejectsUnknownModelOrVariant() {
    AgentModelMapper mapper = mock(AgentModelMapper.class);
    AgentModelRuntimeConfigParser parser = new AgentModelRuntimeConfigParser(new ObjectMapper());
    AgentModelDO model = new AgentModelDO();
    model.setId(7L);
    model.setConfigJson(parser.encode(executableConfig()));
    when(mapper.getById(7L)).thenReturn(model);

    AgentModelDefaultVariantResolver resolver =
        new AgentModelDefaultVariantResolver(mapper, parser);

    assertEquals("default", resolver.resolve(7L, null));
    assertEquals("default", resolver.resolve(7L, " default "));
    assertEquals(
        "unknown agent model variant: modelId=7, variant=missing",
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(7L, "missing"))
            .getMessage());
    assertEquals(
        "unknown agent model: 8",
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(8L, "default"))
            .getMessage());
  }
}
