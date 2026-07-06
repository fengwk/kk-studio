package fun.fengwk.kkstudio.core.agent.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelUpdateDTO;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelUpdateDTO;
import org.junit.jupiter.api.Test;

/**
 * AgentModelMutationFactory 的聚焦行为测试。
 *
 * @author fengwk
 */
public class AgentModelMutationFactoryTest {

  /** 校验创建 mutation 会标准化 provider/name，并补齐 model 默认 variant 配置。 */
  @Test
  public void shouldCreateModelWithNormalizedDefaults() {
    AgentModelMutationFactory factory = newFactory();
    AgentModelCreateDTO createDTO = new AgentModelCreateDTO();
    createDTO.setProvider("  openai  ");
    createDTO.setName("  gpt-4.1  ");
    createDTO.setDescription("  flagship  ");

    AgentModelMutationFactory.Mutation mutation = factory.newCreateMutation(createDTO);
    AgentModel model = factory.newModel(11L, mutation);

    assertEquals("openai", mutation.providerName());
    assertEquals("gpt-4.1", mutation.name());
    assertEquals("flagship", mutation.description());
    assertEquals("default", mutation.defaultVariant());
    assertEquals("[{\"name\":\"default\"}]", mutation.variantsJson());
    assertNotNull(model.getId());
    assertEquals(11L, model.getProviderId());
    assertEquals("gpt-4.1", model.getName());
    assertEquals("default", model.getDefaultVariant());
  }

  /** 校验更新 mutation 会沿用旧名称，并把空白描述收敛为 null。 */
  @Test
  public void shouldApplyUpdateMutationWithCurrentNameFallback() {
    AgentModelMutationFactory factory = newFactory();
    AgentModelUpdateDTO updateDTO = new AgentModelUpdateDTO();
    updateDTO.setName(" ");
    updateDTO.setDescription(" ");
    updateDTO.setCapabilitiesJson("{\"vision\":true}");
    updateDTO.setLimitJson("{\"maxTokens\":128000}");
    updateDTO.setPricingJson("{\"input\":2}");
    updateDTO.setDefaultVariant("  stable  ");
    updateDTO.setVariantsJson("[{\"name\":\"stable\"}]");

    AgentModel model = new AgentModel();
    factory.apply(model, factory.newUpdateMutation("gpt-4.1", updateDTO));

    assertEquals("gpt-4.1", model.getName());
    assertNull(model.getDescription());
    assertEquals("{\"vision\":true}", model.getCapabilitiesJson());
    assertEquals("{\"maxTokens\":128000}", model.getLimitJson());
    assertEquals("{\"input\":2}", model.getPricingJson());
    assertEquals("stable", model.getDefaultVariant());
    assertEquals("[{\"name\":\"stable\"}]", model.getVariantsJson());
  }

  /** 校验非法 JSON 与缺失主键字段会被及时拒绝，避免生成半残 model。 */
  @Test
  public void shouldRejectInvalidArguments() {
    AgentModelMutationFactory factory = newFactory();
    AgentModelCreateDTO createDTO = new AgentModelCreateDTO();
    createDTO.setProvider("openai");
    createDTO.setName("gpt-4.1");

    assertThrows(IllegalArgumentException.class, () -> new AgentModelMutationFactory(null));
    assertThrows(IllegalArgumentException.class, () -> factory.newCreateMutation(null));

    createDTO.setName(" ");
    assertThrows(IllegalArgumentException.class, () -> factory.newCreateMutation(createDTO));

    createDTO.setName("gpt-4.1");
    createDTO.setProvider(" ");
    assertThrows(IllegalArgumentException.class, () -> factory.newCreateMutation(createDTO));

    createDTO.setProvider("openai");
    createDTO.setCapabilitiesJson("[]");
    assertThrows(IllegalArgumentException.class, () -> factory.newCreateMutation(createDTO));

    createDTO.setCapabilitiesJson(null);
    createDTO.setVariantsJson("{}");
    assertThrows(IllegalArgumentException.class, () -> factory.newCreateMutation(createDTO));

    AgentModelUpdateDTO updateDTO = new AgentModelUpdateDTO();
    updateDTO.setVariantsJson("bad-json");
    assertThrows(
        IllegalArgumentException.class, () -> factory.newUpdateMutation("gpt-4.1", updateDTO));
    assertThrows(
        IllegalArgumentException.class,
        () -> factory.newModel(0L, factory.newCreateMutation(baseCreate())));
    assertThrows(IllegalArgumentException.class, () -> factory.newModel(1L, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> factory.apply(null, factory.newCreateMutation(baseCreate())));
    assertThrows(IllegalArgumentException.class, () -> factory.apply(new AgentModel(), null));
  }

  private static AgentModelCreateDTO baseCreate() {
    AgentModelCreateDTO createDTO = new AgentModelCreateDTO();
    createDTO.setProvider("openai");
    createDTO.setName("gpt-4.1");
    return createDTO;
  }

  private static AgentModelMutationFactory newFactory() {
    return new AgentModelMutationFactory(new AgentEditableSupport(new ObjectMapper()));
  }
}
