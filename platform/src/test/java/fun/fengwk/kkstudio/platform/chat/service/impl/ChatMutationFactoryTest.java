package fun.fengwk.kkstudio.platform.chat.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.chat.service.model.Chat;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatUpdateDTO;

import java.util.List;

class ChatMutationFactoryTest {

  @Test
  void normalizesVisibleConfigurationAndDefaultsYoloToFalseWhenOmitted() {
    ChatMutationFactory factory = factory();
    ChatCreateDTO create = new ChatCreateDTO();
    create.setTitle("t".repeat(256));
    create.setAgentName("\u2003assistant\u2003");
    assertThrows(AiValidationException.class, () -> factory.newChat(create));

    create.setAgentName("assistant");
    Chat chat = factory.newChat(create);

    assertEquals("t".repeat(256), chat.getTitle());
    assertEquals("assistant", chat.getAgentName());
    // 省略 YOLO 与 Environment 时：YOLO 为 false，默认环境为空。
    assertFalse(chat.isYoloEnabled());
    assertNull(chat.getEnvironmentName());

    ChatCreateDTO explicit = new ChatCreateDTO();
    explicit.setTitle("explicit");
    explicit.setAgentName("assistant");
    explicit.setYoloEnabled(true);
    explicit.setEnvironmentName("env-a");
    Chat explicitChat = factory.newChat(explicit);
    assertTrue(explicitChat.isYoloEnabled());
    assertEquals("env-a", explicitChat.getEnvironmentName());
  }

  @Test
  void enforcesChatTitleSchemaLimits() {
    ChatMutationFactory factory = factory();
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

  /** Environment name 复用 BranchSettings 的 canonical 规则；显式 null 表示清空默认环境。 */
  @Test
  void validatesEnvironmentNameCanonicalFormAndAllowsExplicitClear() {
    ChatMutationFactory factory = factory();
    Chat chat = chatWith("assistant");

    for (String invalid : List.of("", "  ", " env", "env ", "a/b", "e".repeat(65))) {
      ChatUpdateDTO update = new ChatUpdateDTO();
      update.setEnvironmentName(invalid);
      assertThrows(AiValidationException.class, () -> factory.apply(chat, update), invalid);
    }

    chat.setEnvironmentName("env-a");
    ChatUpdateDTO clear = new ChatUpdateDTO();
    clear.setEnvironmentName(null);
    factory.apply(chat, clear);
    assertNull(chat.getEnvironmentName());
  }

  /** 更新省略 Environment/YOLO 保留当前值；显式 null YOLO 被拒绝，显式 null Environment 清空。 */
  @Test
  void updateRetainsOmittedFieldsAndRejectsExplicitNullYolo() {
    ChatMutationFactory factory = factory();
    Chat chat = chatWith("assistant");
    chat.setEnvironmentName("env-a");
    chat.setYoloEnabled(true);

    ChatUpdateDTO omitted = new ChatUpdateDTO();
    omitted.setTitle("next");
    factory.apply(chat, omitted);
    assertEquals("env-a", chat.getEnvironmentName());
    assertTrue(chat.isYoloEnabled());

    ChatUpdateDTO nullYolo = new ChatUpdateDTO();
    nullYolo.setYoloEnabled(null);
    assertThrows(AiValidationException.class, () -> factory.apply(chat, nullYolo));

    ChatUpdateDTO nullEnvironment = new ChatUpdateDTO();
    nullEnvironment.setEnvironmentName(null);
    factory.apply(chat, nullEnvironment);
    assertNull(chat.getEnvironmentName());
  }

  /** Chat 不再承载 workspace：创建与更新都不接受该字段，且 Chat 行本身不存在目录状态。 */
  @Test
  void rejectsLegacyWorkspacePathFieldOnCreateAndUpdate() {
    ChatMutationFactory factory = factory();

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

  private static Chat chatWith(String agentName) {
    Chat chat = new Chat();
    chat.setTitle("title");
    chat.setAgentName(agentName);
    return chat;
  }

  private static ChatMutationFactory factory() {
    return new ChatMutationFactory(new AgentEditableSupport(new ObjectMapper()));
  }
}
