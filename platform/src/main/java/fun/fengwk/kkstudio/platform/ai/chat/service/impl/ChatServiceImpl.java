package fun.fengwk.kkstudio.platform.ai.chat.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.ai.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.platform.ai.chat.service.ChatIds;
import fun.fengwk.kkstudio.platform.ai.chat.service.ChatService;
import fun.fengwk.kkstudio.platform.ai.chat.service.converter.ChatConverter;
import fun.fengwk.kkstudio.platform.ai.chat.service.model.Chat;
import fun.fengwk.kkstudio.platform.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.ai.error.AiValidationException;
import fun.fengwk.kkstudio.platform.ai.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.ai.error.CatalogVersions;
import fun.fengwk.kkstudio.platform.studio.HarnessSessionDeletionService;
import fun.fengwk.kkstudio.platform.studio.StudioOwner;
import fun.fengwk.kkstudio.platform.studio.StudioOwnerType;
import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatUpdateDTO;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Chat CRUD 服务；deleteChat 是深删除：排他锁定 Chat 后经共享 {@link HarnessSessionDeletionService} 删除全部归属 Session。
 */
@Service
public class ChatServiceImpl implements ChatService {

  private static final String RESOURCE = "chat";

  private final ChatRepository repository;
  private final ChatConverter converter;
  private final ChatMutationFactory mutationFactory;
  private final ChatGuard guard;
  private final HarnessSessionDeletionService sessionDeletionService;

  public ChatServiceImpl(
      ChatRepository repository,
      ChatConverter converter,
      ChatMutationFactory mutationFactory,
      ChatGuard guard,
      HarnessSessionDeletionService sessionDeletionService) {
    this.repository = repository;
    this.converter = converter;
    this.mutationFactory = mutationFactory;
    this.guard = guard;
    this.sessionDeletionService = Objects.requireNonNull(sessionDeletionService);
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
    guard.ensureAgentExists(chat.getAgentName());
    if (!repository.create(chat)) {
      throw new IllegalStateException("create chat failed");
    }
    return converter.convert(repository.getById(chat.getId()));
  }

  @Override
  @Transactional
  public ChatDTO updateChat(String id, ChatUpdateDTO updateDTO) {
    String rawExpected = updateDTO == null ? null : updateDTO.getExpectedVersion();
    if (rawExpected == null) {
      throw new AiValidationException(RESOURCE, "expectedVersion is required");
    }
    long expected = CatalogVersions.parse(rawExpected, "expectedVersion");
    Chat existing = guard.requireChat(id);
    ensureExpectedVersion(existing, id, rawExpected, expected);
    boolean agentNameProvided = updateDTO.getAgentName() != null;
    mutationFactory.apply(existing, updateDTO);
    if (agentNameProvided) {
      guard.ensureAgentExists(existing.getAgentName());
    }
    if (!repository.updateById(existing, expected)) {
      Chat reread = repository.getById(existing.getId());
      if (reread == null) {
        throw new AiResourceNotFoundException(RESOURCE, RESOURCE + " not found: " + id);
      }
      throw new AiVersionConflictException(
          RESOURCE, id, rawExpected, CatalogVersions.format(reread.getVersion()));
    }
    return converter.convert(repository.getById(existing.getId()));
  }

  /**
   * 深删除：排他锁定 Chat 行（版本 CAS 保留）→ 共享 {@link HarnessSessionDeletionService} 在同一应用事务内删除全部 归属
   * Session（Work/Invocation/Command/Thread/Entry/Session 与 relation、blob 引用对账）→ CAS 删除 Chat 行。 绝不依赖
   * ON DELETE CASCADE 旁路 blob 引用计数。
   */
  @Override
  @Transactional
  public void deleteChat(String id, String expectedVersion) {
    long expected = CatalogVersions.parse(expectedVersion, "expectedVersion");
    UUID parsed = ChatIds.parseUuid(id, "id");
    Chat locked = repository.lockById(parsed);
    if (locked == null) {
      throw new AiResourceNotFoundException(RESOURCE, RESOURCE + " not found: " + id);
    }
    ensureExpectedVersion(locked, id, expectedVersion, expected);
    sessionDeletionService.deleteSessionsByOwner(
        new StudioOwner(StudioOwnerType.CHAT, locked.getId()));
    if (!repository.deleteById(locked.getId(), expected)) {
      throw new IllegalStateException("chat " + id + " changed under lock");
    }
  }

  private static void ensureExpectedVersion(
      Chat chat, String id, String expectedVersion, long expected) {
    if (chat.getVersion() != expected) {
      throw new AiVersionConflictException(
          RESOURCE, id, expectedVersion, CatalogVersions.format(chat.getVersion()));
    }
  }
}
