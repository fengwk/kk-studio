package fun.fengwk.kkstudio.core.chat.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.chat.service.model.Chat;
import fun.fengwk.kkstudio.core.persistence.id.PostgresqlSequenceIdGenerator;
import fun.fengwk.kkstudio.share.model.ChatCreateDTO;
import fun.fengwk.kkstudio.share.model.ChatUpdateDTO;

class ChatMutationFactoryTest {

  @Test
  void enforcesChatTitleSchemaLimitAfterNormalization() {
    ChatMutationFactory factory = factory();
    ChatCreateDTO accepted = new ChatCreateDTO();
    accepted.setTitle("t".repeat(256));
    Chat chat = factory.newChat(accepted);
    assertEquals("t".repeat(256), chat.getTitle());

    ChatCreateDTO oversized = new ChatCreateDTO();
    oversized.setTitle("t".repeat(257));
    assertThrows(AiValidationException.class, () -> factory.newChat(oversized));

    ChatUpdateDTO update = new ChatUpdateDTO();
    update.setTitle("t".repeat(257));
    assertThrows(AiValidationException.class, () -> factory.apply(chat, update));
  }

  private static ChatMutationFactory factory() {
    PostgresqlSequenceIdGenerator idGenerator = Mockito.mock(PostgresqlSequenceIdGenerator.class);
    when(idGenerator.next()).thenReturn(1L);
    return new ChatMutationFactory(new AgentEditableSupport(new ObjectMapper()), idGenerator);
  }
}
