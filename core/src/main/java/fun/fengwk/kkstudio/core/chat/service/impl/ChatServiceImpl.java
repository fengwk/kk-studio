package fun.fengwk.kkstudio.core.chat.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator;
import fun.fengwk.kkstudio.core.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.core.chat.service.ChatService;
import fun.fengwk.kkstudio.core.chat.service.converter.ChatConverter;
import fun.fengwk.kkstudio.core.chat.service.model.Chat;
import fun.fengwk.kkstudio.core.harness.session.service.impl.HarnessSessionDtoConverter;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import fun.fengwk.kkstudio.share.model.ChatCreateDTO;
import fun.fengwk.kkstudio.share.model.ChatDTO;
import fun.fengwk.kkstudio.share.model.ChatUpdateDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/** Chat CRUD and Chat↔Session membership service. */
@Service
public class ChatServiceImpl implements ChatService {

  private final ChatRepository repository;
  private final ChatConverter converter;
  private final ChatMutationFactory mutationFactory;
  private final ChatGuard guard;
  private final HarnessSessionMapper harnessSessionMapper;
  private final HarnessSessionDtoConverter harnessSessionDtoConverter;

  public ChatServiceImpl(
      ChatRepository repository,
      ChatConverter converter,
      ChatMutationFactory mutationFactory,
      ChatGuard guard,
      HarnessSessionMapper harnessSessionMapper,
      HarnessSessionDtoConverter harnessSessionDtoConverter) {
    this.repository = repository;
    this.converter = converter;
    this.mutationFactory = mutationFactory;
    this.guard = guard;
    this.harnessSessionMapper = harnessSessionMapper;
    this.harnessSessionDtoConverter = harnessSessionDtoConverter;
  }

  @Override
  public List<ChatDTO> listChats() {
    return repository.listNewestFirst().stream()
        .map(converter::convert)
        .collect(Collectors.toList());
  }

  @Override
  public ChatDTO getChat(String id) {
    return converter.convert(guard.requireChat(id));
  }

  @Override
  @Transactional
  public ChatDTO createChat(ChatCreateDTO createDTO) {
    Chat chat = mutationFactory.newChat(createDTO);
    guard.ensureDefaultAgentExistsIfPresent(chat.getDefaultAgentId());
    if (!repository.create(chat)) {
      throw new IllegalStateException("create chat failed");
    }
    return converter.convert(repository.getById(chat.getId()));
  }

  @Override
  @Transactional
  public ChatDTO updateChat(String id, ChatUpdateDTO updateDTO) {
    Chat existing = guard.requireChat(id);
    mutationFactory.apply(existing, updateDTO);
    guard.ensureDefaultAgentExistsIfPresent(existing.getDefaultAgentId());
    if (!repository.updateById(existing)) {
      throw new IllegalStateException("update chat failed: " + id);
    }
    return converter.convert(repository.getById(existing.getId()));
  }

  @Override
  @Transactional
  public void deleteChat(String id) {
    Chat existing = guard.requireChat(id);
    repository.deleteMembershipsByChatId(existing.getId());
    if (!repository.deleteById(existing.getId())) {
      throw new IllegalStateException("delete chat failed: " + id);
    }
  }

  @Override
  public List<HarnessSessionDTO> listSessions(String chatId) {
    Chat chat = guard.requireChat(chatId);
    List<Long> sessionIds = repository.listSessionIds(chat.getId());
    List<HarnessSessionDTO> result = new ArrayList<>(sessionIds.size());
    for (Long sessionId : sessionIds) {
      HarnessSessionDO row = harnessSessionMapper.find(sessionId);
      if (row != null) {
        result.add(harnessSessionDtoConverter.convert(row));
      }
    }
    return result;
  }

  /**
   * Attaches a Session to a Chat. Duplicate membership is idempotent: already attached returns the
   * existing Session without re-insert and without updating Chat timestamps again.
   */
  @Override
  @Transactional
  public HarnessSessionDTO attachSession(String chatId, String sessionId) {
    Chat chat = guard.requireChat(chatId);
    HarnessSessionDO session = guard.requireSession(sessionId);
    if (!repository.isSessionAttached(chat.getId(), session.getId())) {
      long membershipId = AgentIdGenerator.nextChatSessionId();
      if (!repository.attachSession(membershipId, chat.getId(), session.getId())) {
        throw new IllegalStateException(
            "attach session failed: chat=" + chatId + ", session=" + sessionId);
      }
      if (!repository.touch(chat.getId())) {
        throw new IllegalStateException("touch chat failed after attach: " + chatId);
      }
    }
    return harnessSessionDtoConverter.convert(session);
  }

  @Override
  @Transactional
  public void detachSession(String chatId, String sessionId) {
    Chat chat = guard.requireChat(chatId);
    HarnessSessionDO session = guard.requireSession(sessionId);
    if (!repository.detachSession(chat.getId(), session.getId())) {
      throw new NoSuchElementException(
          "chat session membership not found: chat=" + chatId + ", session=" + sessionId);
    }
    if (!repository.touch(chat.getId())) {
      throw new IllegalStateException("touch chat failed after detach: " + chatId);
    }
  }
}
