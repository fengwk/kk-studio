package fun.fengwk.kkstudio.core.chat.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator;
import fun.fengwk.kkstudio.core.chat.service.ChatIds;
import fun.fengwk.kkstudio.core.chat.service.model.Chat;
import fun.fengwk.kkstudio.share.model.ChatCreateDTO;
import fun.fengwk.kkstudio.share.model.ChatUpdateDTO;

import java.util.function.LongSupplier;

/**
 * Normalizes Chat mutable fields and allocates Chat ids.
 *
 * <p>Update semantics: {@code null} fields preserve the current value; blank strings clear optional
 * fields to null. Non-blank {@code defaultAgentId} is parsed as a positive decimal string; catalog
 * existence is validated by {@link ChatGuard}.
 */
@Component
public class ChatMutationFactory {

  private final AgentEditableSupport editableSupport;
  private final LongSupplier idGenerator;

  @Autowired
  public ChatMutationFactory(AgentEditableSupport editableSupport) {
    this(editableSupport, AgentIdGenerator::nextChatId);
  }

  public ChatMutationFactory(AgentEditableSupport editableSupport, LongSupplier idGenerator) {
    this.editableSupport = editableSupport;
    this.idGenerator = idGenerator;
  }

  public Chat newChat(ChatCreateDTO createDTO) {
    if (createDTO == null) {
      throw new IllegalArgumentException("chat body must not be null");
    }
    Chat chat = new Chat();
    chat.setId(idGenerator.getAsLong());
    chat.setTitle(editableSupport.trimToNull(createDTO.getTitle()));
    chat.setDefaultAgentId(parseOptionalAgentId(createDTO.getDefaultAgentId()));
    return chat;
  }

  public void apply(Chat chat, ChatUpdateDTO updateDTO) {
    if (chat == null) {
      throw new IllegalArgumentException("chat must not be null");
    }
    if (updateDTO == null) {
      throw new IllegalArgumentException("chat body must not be null");
    }
    if (updateDTO.getTitle() != null) {
      chat.setTitle(editableSupport.trimToNull(updateDTO.getTitle()));
    }
    if (updateDTO.getDefaultAgentId() != null) {
      chat.setDefaultAgentId(parseOptionalAgentId(updateDTO.getDefaultAgentId()));
    }
  }

  /** Parses optional agent id text. {@code null} and blank input both map to {@code null}. */
  private Long parseOptionalAgentId(String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return ChatIds.parsePositive(trimmed, "defaultAgentId");
  }
}
