package fun.fengwk.kkstudio.platform.chat.service.impl;

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
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.chat.service.model.Chat;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
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

  /** Chat 不再承载 workspace：创建与更新都不接受该字段，且 Chat 行本身不存在目录状态。 */
  @Test
  void rejectsLegacyWorkspacePathFieldOnCreateAndUpdate() {
    ChatMutationFactory factory = factory(false);

    ChatCreateDTO create = new ChatCreateDTO();
    create.setTitle("valid");
    create.setAgentName("assistant");
    Chat chat = factory.newChat(create);
    assertEquals("valid", chat.getTitle());

    ChatUpdateDTO update = new ChatUpdateDTO();
    update.setTitle("next");
    factory.apply(chat, update);
    assertEquals("next", chat.getTitle());

    // legacy workspace 字段在 DTO 边界就被幂等拒绝，绝不会作为未知字段被静默忽略。
    assertThrows(
        IllegalArgumentException.class,
        () -> create.rejectUnknownField("workspacePath", "src/main"));
    assertThrows(
        IllegalArgumentException.class,
        () -> update.rejectUnknownField("workspacePath", "/absolute"));
    assertThrows(
        IllegalArgumentException.class,
        () -> update.rejectUnknownField("environmentId", "11111111-1111-1111-1111-111111111111"));
  }

  private static ChatMutationFactory factory(boolean defaultYolo) {
    ToolSettingsProvider settings = mock(ToolSettingsProvider.class);
    when(settings.get()).thenReturn(new ToolSettings(Map.of(), defaultYolo));
    return new ChatMutationFactory(new AgentEditableSupport(new ObjectMapper()), settings);
  }
}
