package fun.fengwk.kkstudio.platform.catalog.definition.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionUpdateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentSkillRefDTO;

import java.util.List;

/** 结构化的定义配置在持久化前必须规范化；create/update 共用可编辑 model 引用。 */
public class AgentDefinitionMutationFactoryTest {

  private static final String SOURCE_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

  @Test
  public void shouldPersistCanonicalCapabilityListsAndKeepIdentity() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    AgentDefinitionMutationFactory factory = factory(objectMapper);
    AgentDefinitionConfigDTO config =
        config(
            List.of("browser"),
            List.of(
                new AgentSkillRefDTO(SOURCE_ID, "java"), new AgentSkillRefDTO(SOURCE_ID, "dev")));
    AgentDefinitionCreateDTO create = create("agent", "provider/model", config, "default");

    AgentDefinition definition = factory.newAgent("agent", create);
    AgentDefinitionConfigDTO stored =
        objectMapper.readValue(definition.getConfigJson(), AgentDefinitionConfigDTO.class);
    assertEquals(List.of("browser"), stored.getToolIds());
    assertEquals(
        List.of(new AgentSkillRefDTO(SOURCE_ID, "java"), new AgentSkillRefDTO(SOURCE_ID, "dev")),
        stored.getSkills());
    assertEquals("provider", definition.getModelProviderName());
    assertEquals("model", definition.getModelName());

    AgentDefinitionUpdateDTO update = new AgentDefinitionUpdateDTO();
    update.setDescription("updated");
    update.setModel("other-provider/other-model");
    update.setVariant("quality");
    update.setConfig(config(List.of(), List.of()));
    factory.update(definition, update);
    assertEquals("agent", definition.getName());
    assertEquals("other-provider", definition.getModelProviderName());
    assertEquals("other-model", definition.getModelName());
    assertEquals("quality", definition.getVariant());
  }

  @Test
  public void shouldRejectNonCanonicalCapabilityNames() {
    AgentDefinitionMutationFactory factory = factory(new ObjectMapper());
    AgentDefinitionCreateDTO create =
        create(
            "agent",
            "provider/model",
            config(
                List.of(),
                List.of(
                    new AgentSkillRefDTO(SOURCE_ID, "java"),
                    new AgentSkillRefDTO(SOURCE_ID, "java"))),
            "default");
    AiValidationException error =
        assertThrows(AiValidationException.class, () -> factory.newAgent("agent", create));
    assertEquals(
        "agent definition config skills must not contain duplicates: " + SOURCE_ID + ":java",
        error.getMessage());

    create.setConfig(config(List.of(" read "), List.of()));
    error = assertThrows(AiValidationException.class, () -> factory.newAgent("agent", create));
    assertEquals(
        "agent definition config toolIds must contain canonical AgentToolIds:  read ",
        error.getMessage());
  }

  @Test
  public void shouldRequireCompleteConfigurationAndEnforceLimits() {
    AgentDefinitionMutationFactory factory = factory(new ObjectMapper());
    assertThrows(
        AiValidationException.class, () -> factory.newAgent(" ", new AgentDefinitionCreateDTO()));
    assertThrows(
        AiValidationException.class,
        () ->
            factory.newAgent(
                "\u2003agent\u2003",
                create("\u2003agent\u2003", "provider/model", config(List.of(), List.of()), null)));
    AgentDefinitionCreateDTO missingModel =
        create("agent", null, config(List.of(), List.of()), null);
    assertThrows(AiValidationException.class, () -> factory.newAgent("agent", missingModel));
    missingModel.setModel("no-slash");
    assertThrows(AiValidationException.class, () -> factory.newAgent("agent", missingModel));

    AgentDefinitionCreateDTO incomplete = create("agent", "provider/model", null, null);
    assertThrows(AiValidationException.class, () -> factory.newAgent("agent", incomplete));
    incomplete.setConfig(config(List.of(), List.of()));
    AgentDefinition allowedBlankVariant = factory.newAgent("agent", incomplete);
    assertNull(allowedBlankVariant.getVariant());

    AgentDefinitionCreateDTO oversized =
        create("n".repeat(65), "provider/model", config(List.of(), List.of()), null);
    assertThrows(
        AiValidationException.class, () -> factory.newAgent(oversized.getName(), oversized));
    // description 已放开为 text：超长文本不再被拒绝，也不再截断。
    AgentDefinitionCreateDTO longDescription =
        create("agent", "provider/model", config(List.of(), List.of()), null);
    longDescription.setDescription("d".repeat(4096));
    assertEquals("d".repeat(4096), factory.newAgent("agent", longDescription).getDescription());
    AgentDefinitionCreateDTO pathBreaking =
        create("agent/name", "provider/model", config(List.of(), List.of()), null);
    assertThrows(
        AiValidationException.class, () -> factory.newAgent(pathBreaking.getName(), pathBreaking));
  }

  // 测试意图: 验证 mutation factory 在 body 或 name 为 null 时抛出清晰的 AiValidationException
  @Test
  public void shouldRejectNullPropertiesAndNullName() {
    AgentDefinitionMutationFactory factory = factory(new ObjectMapper());
    assertThrows(AiValidationException.class, () -> factory.newAgent("agent", null));
    AgentDefinitionCreateDTO create =
        create(null, "provider/model", config(List.of(), List.of()), null);
    assertThrows(AiValidationException.class, () -> factory.newAgent(null, create));
  }

  // 测试意图: 验证 mutation factory 解析非法 environmentId 字符串时抛出 AiValidationException
  @Test
  public void shouldRejectInvalidEnvironmentId() {
    AgentDefinitionMutationFactory factory = factory(new ObjectMapper());
    AgentDefinitionCreateDTO create =
        create("agent", "provider/model", config(List.of(), List.of()), null);
    create.setEnvironmentId("not-a-valid-uuid");
    assertThrows(AiValidationException.class, () -> factory.newAgent("agent", create));
  }

  private static AgentDefinitionCreateDTO create(
      String name, String model, AgentDefinitionConfigDTO config, String variant) {
    AgentDefinitionCreateDTO create = new AgentDefinitionCreateDTO();
    create.setName(name);
    create.setModel(model);
    create.setVariant(variant);
    create.setConfig(config);
    return create;
  }

  private static AgentDefinitionConfigDTO config(
      List<String> toolIds, List<AgentSkillRefDTO> skills) {
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setToolIds(toolIds);
    config.setSkills(skills);
    config.setSubagents(List.of());
    return config;
  }

  private AgentDefinitionMutationFactory factory(ObjectMapper objectMapper) {
    return new AgentDefinitionMutationFactory(
        new AgentEditableSupport(objectMapper), new AgentDefinitionConfigCodec(objectMapper));
  }
}
