package fun.fengwk.kkstudio.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.chat.dto.ChatCreateDTO;
import fun.fengwk.kkstudio.chat.dto.ChatUpdateDTO;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;

import java.util.List;

class ChatMutationFactoryTest {

  @Test
  void normalizesVisibleConfigurationAndDefaultsYoloToFalseWhenOmitted() {
    ChatMutationFactory factory = factory();
    ChatCreateDTO create = new ChatCreateDTO();
    create.setTitle("t".repeat(256));
    create.setAgentName("\u2003assistant\u2003");
    assertThrows(DomainException.class, () -> factory.newChat(create));

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
    assertThrows(DomainException.class, () -> factory.newChat(oversized));

    ChatUpdateDTO update = new ChatUpdateDTO();
    update.setTitle("t".repeat(257));
    assertThrows(DomainException.class, () -> factory.apply(chat, update));
  }

  /** Environment name 复用 BranchSettings 的 canonical 规则；显式 null 表示清空默认环境。 */
  @Test
  void validatesEnvironmentNameCanonicalFormAndAllowsExplicitClear() {
    ChatMutationFactory factory = factory();
    Chat chat = chatWith("assistant");

    for (String invalid : List.of("", "  ", " env", "env ", "a/b", "e".repeat(65))) {
      ChatUpdateDTO update = new ChatUpdateDTO();
      update.setTitle("title");
      update.setAgentName("assistant");
      update.setEnvironmentName(invalid);
      assertThrows(DomainException.class, () -> factory.apply(chat, update), invalid);
    }

    chat.setEnvironmentName("env-a");
    ChatUpdateDTO clear = new ChatUpdateDTO();
    clear.setTitle("title");
    clear.setAgentName("assistant");
    clear.setEnvironmentName(null);
    factory.apply(chat, clear);
    assertNull(chat.getEnvironmentName());
  }

  /** 整体替换清空默认环境，但不修改独立的 YOLO 子资源。 */
  @Test
  void replaceClearsEnvironmentAndLeavesYolo() {
    ChatMutationFactory factory = factory();
    Chat chat = chatWith("assistant");
    chat.setEnvironmentName("env-a");
    chat.setYoloEnabled(true);

    ChatUpdateDTO replacement = new ChatUpdateDTO();
    replacement.setTitle("next");
    replacement.setAgentName("assistant");
    factory.apply(chat, replacement);
    assertNull(chat.getEnvironmentName());
    assertTrue(chat.isYoloEnabled());
  }

  private static Chat chatWith(String agentName) {
    Chat chat = new Chat();
    chat.setTitle("title");
    chat.setAgentName(agentName);
    return chat;
  }

  private static ChatMutationFactory factory() {
    return new ChatMutationFactory(new AgentEditableSupport(JsonMapper.builder().build()));
  }
}
