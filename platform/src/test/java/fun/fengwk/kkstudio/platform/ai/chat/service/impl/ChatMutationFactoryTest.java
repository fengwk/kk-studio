package fun.fengwk.kkstudio.platform.ai.chat.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettingsProvider;
import fun.fengwk.kkstudio.platform.ai.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.ai.chat.service.model.Chat;
import fun.fengwk.kkstudio.platform.ai.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatUpdateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.EnvironmentBindingDTO;

import java.util.Map;

class ChatMutationFactoryTest {

  @Test
  void normalizesVisibleConfigurationAndUsesDefaultYoloOnlyWhenOmitted() {
    ChatMutationFactory factory = factory(true);
    ChatCreateDTO create = new ChatCreateDTO();
    create.setTitle("t".repeat(256));
    create.setAgentName("\u2003assistant\u2003");
    assertThrows(AiValidationException.class, () -> factory.newChat(create));

    create.setAgentName("assistant");
    Chat chat = factory.newChat(create);

    assertEquals("t".repeat(256), chat.getTitle());
    assertEquals("assistant", chat.getAgentName());
    assertTrue(chat.isYoloEnabled());

    ChatCreateDTO explicit = new ChatCreateDTO();
    explicit.setTitle("explicit");
    explicit.setAgentName("assistant");
    explicit.setYoloEnabled(false);
    assertFalse(factory.newChat(explicit).isYoloEnabled());
  }

  @Test
  void enforcesChatTitleSchemaLimits() {
    ChatMutationFactory factory = factory(false);
    ChatCreateDTO accepted = new ChatCreateDTO();
    accepted.setTitle("title");
    accepted.setAgentName("assistant");
    Chat chat = factory.newChat(accepted);

    ChatCreateDTO oversized = new ChatCreateDTO();
    oversized.setTitle("t".repeat(257));
    oversized.setAgentName("assistant");
    assertThrows(AiValidationException.class, () -> factory.newChat(oversized));

    ChatUpdateDTO update = new ChatUpdateDTO();
    update.setTitle("t".repeat(257));
    assertThrows(AiValidationException.class, () -> factory.apply(chat, update));

    ChatUpdateDTO clear = new ChatUpdateDTO();
    clear.setYoloEnabled(true);
    factory.apply(chat, clear);
    assertTrue(chat.isYoloEnabled());
  }

  @Test
  void rejectsIncompleteEnvironmentBindingAsValidationNotNpe() {
    // binding 嵌套字段缺失必须抛 AiValidationException（HTTP 400），绝不能把 NPE 漏成 500。
    ChatMutationFactory factory = factory(false);

    ChatCreateDTO createMissingName = new ChatCreateDTO();
    createMissingName.setTitle("env-missing-name");
    createMissingName.setAgentName("assistant");
    createMissingName.setEnvironment(bindingDto(null, "."));
    assertThrows(AiValidationException.class, () -> factory.newChat(createMissingName));

    ChatCreateDTO createMissingPath = new ChatCreateDTO();
    createMissingPath.setTitle("env-missing-path");
    createMissingPath.setAgentName("assistant");
    createMissingPath.setEnvironment(bindingDto("env-1", null));
    assertThrows(AiValidationException.class, () -> factory.newChat(createMissingPath));

    ChatCreateDTO valid = new ChatCreateDTO();
    valid.setTitle("env-valid");
    valid.setAgentName("assistant");
    valid.setEnvironment(bindingDto("env-1", "."));
    Chat chat = factory.newChat(valid);
    ChatUpdateDTO updateMissingName = new ChatUpdateDTO();
    updateMissingName.setEnvironment(bindingDto(null, "."));
    assertThrows(AiValidationException.class, () -> factory.apply(chat, updateMissingName));

    ChatUpdateDTO updateMissingPath = new ChatUpdateDTO();
    updateMissingPath.setEnvironment(bindingDto("env-1", null));
    assertThrows(AiValidationException.class, () -> factory.apply(chat, updateMissingPath));
  }

  private static EnvironmentBindingDTO bindingDto(String name, String workspacePath) {
    EnvironmentBindingDTO dto = new EnvironmentBindingDTO();
    dto.setName(name);
    dto.setWorkspacePath(workspacePath);
    return dto;
  }

  private static ChatMutationFactory factory(boolean defaultYolo) {
    ToolSettingsProvider settings = mock(ToolSettingsProvider.class);
    when(settings.get()).thenReturn(new ToolSettings(Map.of(), defaultYolo));
    return new ChatMutationFactory(new AgentEditableSupport(new ObjectMapper()), settings);
  }
}
