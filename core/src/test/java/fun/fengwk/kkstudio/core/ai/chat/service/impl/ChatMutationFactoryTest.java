package fun.fengwk.kkstudio.core.ai.chat.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.ai.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.core.ai.chat.service.model.Chat;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettingsProvider;
import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatUpdateDTO;

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

  private static ChatMutationFactory factory(boolean defaultYolo) {
    ToolSettingsProvider settings = mock(ToolSettingsProvider.class);
    when(settings.get()).thenReturn(new ToolSettings(Map.of(), defaultYolo));
    return new ChatMutationFactory(new AgentEditableSupport(new ObjectMapper()), settings);
  }
}
