package fun.fengwk.kkstudio.core.ai.chat.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.core.ai.chat.service.ChatIds;
import fun.fengwk.kkstudio.core.ai.chat.service.model.Chat;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.persistence.id.PostgresqlSequenceIdGenerator;
import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatUpdateDTO;

/**
 * Normalizes Chat mutable fields and allocates Chat ids.
 *
 * <p>Update semantics: {@code null} fields preserve the current value. Chat title is required when
 * supplied; blank {@code defaultAgentId} clears that optional field. Non-blank {@code
 * defaultAgentId} is parsed as a positive decimal string; catalog existence is validated by {@link
 * ChatGuard}.
 */
@Component
public class ChatMutationFactory {

  private static final String RESOURCE = "chat";
  private static final int TITLE_MAX_LENGTH = 256;

  private final AgentEditableSupport editableSupport;
  private final PostgresqlSequenceIdGenerator idGenerator;

  public ChatMutationFactory(
      AgentEditableSupport editableSupport, PostgresqlSequenceIdGenerator idGenerator) {
    this.editableSupport = editableSupport;
    this.idGenerator = idGenerator;
  }

  public Chat newChat(ChatCreateDTO createDTO) {
    if (createDTO == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " body must not be null");
    }
    Chat chat = new Chat();
    chat.setId(idGenerator.next());
    String title = editableSupport.trimToNull(createDTO.getTitle());
    if (title == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " title must not be blank");
    }
    editableSupport.validateMaxLength(RESOURCE, "title", title, TITLE_MAX_LENGTH);
    chat.setTitle(title);
    chat.setDefaultAgentId(parseOptionalAgentId(createDTO.getDefaultAgentId()));
    return chat;
  }

  public void apply(Chat chat, ChatUpdateDTO updateDTO) {
    if (chat == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " must not be null");
    }
    if (updateDTO == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " body must not be null");
    }
    if (updateDTO.getTitle() != null) {
      String title = editableSupport.trimToNull(updateDTO.getTitle());
      if (title == null) {
        throw new AiValidationException(RESOURCE, RESOURCE + " title must not be blank");
      }
      editableSupport.validateMaxLength(RESOURCE, "title", title, TITLE_MAX_LENGTH);
      chat.setTitle(title);
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
