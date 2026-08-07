package fun.fengwk.kkstudio.core.ai.chat.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.ai.chat.repo.ChatThreadRepository;
import fun.fengwk.kkstudio.core.ai.chat.service.ChatThreadService;
import fun.fengwk.kkstudio.core.ai.chat.service.model.Chat;

import java.util.List;

/** Chat↔Thread 历史关联的应用边界。 */
@Service
public class ChatThreadServiceImpl implements ChatThreadService {

  private final ChatGuard chatGuard;
  private final ChatThreadRepository chatThreadRepository;

  public ChatThreadServiceImpl(ChatGuard chatGuard, ChatThreadRepository chatThreadRepository) {
    this.chatGuard = chatGuard;
    this.chatThreadRepository = chatThreadRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public void requireChat(String chatId) {
    chatGuard.requireChat(chatId);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Long> listThreadIds(String chatId) {
    Chat chat = chatGuard.requireChat(chatId);
    return chatThreadRepository.listThreadIds(chat.getId());
  }

  @Override
  @Transactional
  public void associateThread(String chatId, long threadId) {
    Chat chat = chatGuard.requireChat(chatId);
    // ON CONFLICT DO NOTHING 使重复关联幂等。
    chatThreadRepository.associate(chat.getId(), threadId);
  }
}
