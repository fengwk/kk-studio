package fun.fengwk.kkstudio.core.chat.service;

import fun.fengwk.kkstudio.share.model.ChatCreateDTO;
import fun.fengwk.kkstudio.share.model.ChatDTO;
import fun.fengwk.kkstudio.share.model.ChatUpdateDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionDTO;

import java.util.List;

/** Chat collection CRUD and Session membership surface. */
public interface ChatService {

  List<ChatDTO> listChats();

  ChatDTO getChat(String id);

  ChatDTO createChat(ChatCreateDTO createDTO);

  ChatDTO updateChat(String id, ChatUpdateDTO updateDTO);

  void deleteChat(String id);

  List<HarnessSessionDTO> listSessions(String chatId);

  HarnessSessionDTO attachSession(String chatId, String sessionId);

  void detachSession(String chatId, String sessionId);
}
