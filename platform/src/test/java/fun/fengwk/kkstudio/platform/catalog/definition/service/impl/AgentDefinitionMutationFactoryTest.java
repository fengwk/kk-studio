package fun.fengwk.kkstudio.platform.catalog.definition.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.catalog.definition.builtin.BuiltinAgentDefinitions;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionType;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionUpdateDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillRefDTO;

import java.util.List;

/** 结构化的定义配置在持久化前必须规范化；create/update 共用可编辑 model 引用。 */
public class AgentDefinitionMutationFactoryTest {

  @Test
  public void shouldPersistCanonicalCapabilityListsAndKeepIdentity() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    AgentDefinitionMutationFactory factory = factory(objectMapper);
    AgentDefinitionConfigDTO config =
        config(List.of("browser"), List.of(skillRef("tools", "java"), skillRef("tools", "dev")));
    AgentDefinitionCreateDTO create = create("agent", "provider/model", config, "default");

    AgentDefinition definition = factory.newUserAgent("agent", create);
    AgentDefinitionConfigDTO stored =
        objectMapper.readValue(definition.getConfigJson(), AgentDefinitionConfigDTO.class);
    assertEquals(List.of("browser"), stored.getTools());
    assertEquals(List.of(skillRef("tools", "java"), skillRef("tools", "dev")), stored.getSkills());
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
            config(List.of(), List.of(skillRef("tools", "java"), skillRef("tools", "java"))),
            "default");
    AiValidationException error =
        assertThrows(AiValidationException.class, () -> factory.newUserAgent("agent", create));
    assertEquals(
        "agent definition config skills must not contain duplicates: tools/java",
        error.getMessage());

    create.setConfig(config(List.of(" read "), List.of()));
    error = assertThrows(AiValidationException.class, () -> factory.newUserAgent("agent", create));
    assertEquals(
        "agent definition config tools must contain valid model-visible tool names:  read ",
        error.getMessage());
  }

  @Test
  public void shouldRequireCompleteConfigurationAndEnforceLimits() {
    AgentDefinitionMutationFactory factory = factory(new ObjectMapper());
    assertThrows(
        AiValidationException.class,
        () -> factory.newUserAgent(" ", new AgentDefinitionCreateDTO()));
    assertThrows(
        AiValidationException.class,
        () ->
            factory.newUserAgent(
                "\u2003agent\u2003",
                create("\u2003agent\u2003", "provider/model", config(List.of(), List.of()), null)));
    AgentDefinitionCreateDTO missingModel =
        create("agent", null, config(List.of(), List.of()), null);
    assertThrows(AiValidationException.class, () -> factory.newUserAgent("agent", missingModel));
    missingModel.setModel("no-slash");
    assertThrows(AiValidationException.class, () -> factory.newUserAgent("agent", missingModel));

    AgentDefinitionCreateDTO incomplete = create("agent", "provider/model", null, null);
    assertThrows(AiValidationException.class, () -> factory.newUserAgent("agent", incomplete));
    incomplete.setConfig(config(List.of(), List.of()));
    AgentDefinition allowedBlankVariant = factory.newUserAgent("agent", incomplete);
    assertNull(allowedBlankVariant.getVariant());

    AgentDefinitionCreateDTO oversized =
        create("n".repeat(65), "provider/model", config(List.of(), List.of()), null);
    assertThrows(
        AiValidationException.class, () -> factory.newUserAgent(oversized.getName(), oversized));
    // description 已放开为 text：超长文本不再被拒绝，也不再截断。
    AgentDefinitionCreateDTO longDescription =
        create("agent", "provider/model", config(List.of(), List.of()), null);
    longDescription.setDescription("d".repeat(4096));
    assertEquals("d".repeat(4096), factory.newUserAgent("agent", longDescription).getDescription());
    AgentDefinitionCreateDTO pathBreaking =
        create("agent/name", "provider/model", config(List.of(), List.of()), null);
    assertThrows(
        AiValidationException.class,
        () -> factory.newUserAgent(pathBreaking.getName(), pathBreaking));
  }

  /** 测试意图：内置 Agent 允许显式未配置模型，但 variant 必须同时为空；用户 Agent 仍必须提供模型。 */
  @Test
  public void shouldAllowUnconfiguredBuiltinModel() {
    AgentDefinitionMutationFactory factory = factory(new ObjectMapper());
    AgentDefinitionCreateDTO create =
        create(BuiltinAgentDefinitions.COMPACTION_NAME, null, config(List.of(), List.of()), null);
    AgentDefinition builtin =
        factory.newBuiltinAgent(BuiltinAgentDefinitions.COMPACTION_NAME, create);
    assertEquals(AgentDefinitionType.BUILTIN, builtin.getType());
    assertNull(builtin.getModelProviderName());
    assertNull(builtin.getModelName());
    assertNull(builtin.getVariant());

    create.setVariant("default");
    assertThrows(
        AiValidationException.class,
        () -> factory.newBuiltinAgent(BuiltinAgentDefinitions.COMPACTION_NAME, create));

    AgentDefinitionCreateDTO user = create("agent", null, config(List.of(), List.of()), null);
    assertThrows(AiValidationException.class, () -> factory.newUserAgent("agent", user));
  }

  // 测试意图: 验证 mutation factory 在 body 或 name 为 null 时抛出清晰的 AiValidationException
  @Test
  public void shouldRejectNullPropertiesAndNullName() {
    AgentDefinitionMutationFactory factory = factory(new ObjectMapper());
    assertThrows(AiValidationException.class, () -> factory.newUserAgent("agent", null));
    AgentDefinitionCreateDTO create =
        create(null, "provider/model", config(List.of(), List.of()), null);
    assertThrows(AiValidationException.class, () -> factory.newUserAgent(null, create));
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

  private static AgentDefinitionConfigDTO config(List<String> tools, List<SkillRefDTO> skills) {
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(tools);
    config.setSkills(skills);
    config.setSubagents(List.of());
    return config;
  }

  private static SkillRefDTO skillRef(String packageName, String name) {
    SkillRefDTO ref = new SkillRefDTO();
    ref.setPackageName(packageName);
    ref.setName(name);
    return ref;
  }

  private AgentDefinitionMutationFactory factory(ObjectMapper objectMapper) {
    return new AgentDefinitionMutationFactory(
        new AgentEditableSupport(objectMapper), new AgentDefinitionConfigCodec(objectMapper));
  }
}
