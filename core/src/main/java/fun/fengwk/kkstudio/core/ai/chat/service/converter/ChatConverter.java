package fun.fengwk.kkstudio.core.ai.chat.service.converter;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.chat.service.ChatIds;
import fun.fengwk.kkstudio.core.ai.chat.service.model.Chat;
import fun.fengwk.kkstudio.core.ai.error.CatalogVersions;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.share.ai.chat.ChatDTO;
import fun.fengwk.kkstudio.share.ai.runtime.EnvironmentBindingDTO;

/** 将 Chat 领域行转换为公开 DTO。 */
@Component
public class ChatConverter {

  public ChatDTO convert(Chat chat) {
    if (chat == null) {
      return null;
    }
    ChatDTO dto = new ChatDTO();
    dto.setId(ChatIds.format(chat.getId()));
    dto.setTitle(chat.getTitle());
    dto.setAgentName(chat.getAgentName());
    dto.setEnvironment(toEnvironmentDto(chat.getEnvironment()));
    dto.setYoloEnabled(chat.isYoloEnabled());
    dto.setVersion(CatalogVersions.format(chat.getVersion()));
    dto.setCreateTime(chat.getCreateTime());
    dto.setUpdateTime(chat.getUpdateTime());
    return dto;
  }

  private static EnvironmentBindingDTO toEnvironmentDto(EnvironmentBinding binding) {
    if (binding == null) {
      return null;
    }
    EnvironmentBindingDTO dto = new EnvironmentBindingDTO();
    dto.setName(binding.environmentName().value());
    dto.setWorkspacePath(binding.workspacePath());
    return dto;
  }
}
