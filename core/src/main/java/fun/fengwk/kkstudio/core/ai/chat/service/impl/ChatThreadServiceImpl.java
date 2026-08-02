package fun.fengwk.kkstudio.core.ai.chat.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.ai.chat.repo.ChatThreadRepository;
import fun.fengwk.kkstudio.core.ai.chat.service.ChatThreadService;
import fun.fengwk.kkstudio.core.ai.chat.service.model.Chat;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.core.ai.runtime.session.support.HarnessIds;
import fun.fengwk.kkstudio.core.ai.runtime.thread.service.HarnessThreadCommandService;
import fun.fengwk.kkstudio.core.ai.runtime.thread.service.HarnessThreadQueryService;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.api.CursorPageDTO;

/** Application boundary for Chat history aggregation over durable Harness Threads. */
@Service
public class ChatThreadServiceImpl implements ChatThreadService {

  private static final String THREAD_RESOURCE = "thread";

  private final ChatGuard chatGuard;
  private final ChatThreadRepository chatThreadRepository;
  private final HarnessThreadCommandService threadCommandService;
  private final HarnessThreadQueryService threadQueryService;

  public ChatThreadServiceImpl(
      ChatGuard chatGuard,
      ChatThreadRepository chatThreadRepository,
      HarnessThreadCommandService threadCommandService,
      HarnessThreadQueryService threadQueryService) {
    this.chatGuard = chatGuard;
    this.chatThreadRepository = chatThreadRepository;
    this.threadCommandService = threadCommandService;
    this.threadQueryService = threadQueryService;
  }

  @Override
  @Transactional(readOnly = true)
  public CursorPageDTO<HarnessThreadDTO> listThreads(
      String chatId, String sort, String cursor, Integer limit) {
    Chat chat = chatGuard.requireChat(chatId);
    return threadQueryService.listByChat(chat.getId(), sort, cursor, limit);
  }

  @Override
  @Transactional
  public HarnessThreadDTO createThread(String chatId) {
    Chat chat = chatGuard.requireChat(chatId);
    HarnessThreadDTO created = threadCommandService.createThread(chat.getTitle());
    long threadId = HarnessIds.parsePositive(created.getThreadId(), "threadId");
    if (!chatThreadRepository.associate(chat.getId(), threadId)) {
      throw new IllegalStateException("new Thread association was not inserted: " + threadId);
    }
    return threadQueryService.getThread(Long.toString(threadId));
  }

  @Override
  @Transactional
  public void associateThread(String chatId, String threadId) {
    Chat chat = chatGuard.requireChat(chatId);
    long parsedThreadId = HarnessIds.parsePositive(threadId, "threadId");
    requireThread(threadId);
    // ON CONFLICT DO NOTHING makes repeated association idempotent.
    chatThreadRepository.associate(chat.getId(), parsedThreadId);
  }

  private void requireThread(String threadId) {
    try {
      threadQueryService.getThread(threadId);
    } catch (IllegalArgumentException error) {
      if (error.getMessage() != null && error.getMessage().startsWith("unknown thread:")) {
        throw new AiResourceNotFoundException(
            THREAD_RESOURCE, THREAD_RESOURCE + " not found: " + threadId, error);
      }
      throw error;
    }
  }
}
