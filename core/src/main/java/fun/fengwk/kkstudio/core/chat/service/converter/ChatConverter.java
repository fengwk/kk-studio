package fun.fengwk.kkstudio.core.chat.service.converter;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.error.CatalogVersions;
import fun.fengwk.kkstudio.core.chat.service.ChatIds;
import fun.fengwk.kkstudio.core.chat.service.model.Chat;
import fun.fengwk.kkstudio.share.model.ChatDTO;

/** Converts Chat domain rows to public DTOs. */
@Component
public class ChatConverter {

  public ChatDTO convert(Chat chat) {
    if (chat == null) {
      return null;
    }
    ChatDTO dto = new ChatDTO();
    dto.setId(ChatIds.format(chat.getId()));
    dto.setTitle(chat.getTitle());
    dto.setDefaultAgentId(
        chat.getDefaultAgentId() == null ? null : ChatIds.format(chat.getDefaultAgentId()));
    dto.setVersion(CatalogVersions.format(chat.getVersion()));
    dto.setCreateTime(chat.getCreateTime());
    dto.setUpdateTime(chat.getUpdateTime());
    return dto;
  }
}
