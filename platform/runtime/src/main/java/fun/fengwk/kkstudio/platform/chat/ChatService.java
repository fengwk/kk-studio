package fun.fengwk.kkstudio.platform.chat;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.platform.orchestration.SessionDeletionOrchestrator;
import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatUpdateDTO;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Chat CRUD 服务；deleteChat 是深删除：排他锁定 Chat 后经共享 {@link SessionDeletionOrchestrator} 删除全部归属 Session。
 */
@Service
public class ChatService {

  private static final String RESOURCE = "chat";

  private final ChatRepository repository;
  private final ChatMutationFactory mutationFactory;
  private final ChatGuard guard;
  private final SessionDeletionOrchestrator sessionDeletionService;

  public ChatService(
      ChatRepository repository,
      ChatMutationFactory mutationFactory,
      ChatGuard guard,
      SessionDeletionOrchestrator sessionDeletionService) {
    this.repository = repository;
    this.mutationFactory = mutationFactory;
    this.guard = guard;
    this.sessionDeletionService = Objects.requireNonNull(sessionDeletionService);
  }

  public List<ChatDTO> listChats() {
    return repository.listNewestFirst().stream()
        .map(ChatService::convert)
        .collect(Collectors.toList());
  }

  public ChatDTO getChat(String id) {
    return convert(guard.requireChat(id));
  }

  @Transactional
  public ChatDTO createChat(ChatCreateDTO createDTO) {
    Chat chat = mutationFactory.newChat(createDTO);
    guard.ensureAgentExists(chat.getAgentName());
    guard.ensureEnvironmentExists(chat.getEnvironmentName());
    if (!repository.create(chat)) {
      throw new IllegalStateException("create chat failed");
    }
    return convert(repository.getById(chat.getId()));
  }

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
    boolean environmentNameProvided = updateDTO.isEnvironmentNameProvided();
    mutationFactory.apply(existing, updateDTO);
    if (agentNameProvided) {
      guard.ensureAgentExists(existing.getAgentName());
    }
    if (environmentNameProvided) {
      guard.ensureEnvironmentExists(existing.getEnvironmentName());
    }
    if (!repository.updateById(existing, expected)) {
      Chat reread = repository.getById(existing.getId());
      if (reread == null) {
        throw new AiResourceNotFoundException(RESOURCE);
      }
      throw new AiVersionConflictException(
          RESOURCE, rawExpected, CatalogVersions.format(reread.getVersion()));
    }
    return convert(repository.getById(existing.getId()));
  }

  /**
   * 深删除：排他锁定 Chat 行（版本 CAS 保留）→ 共享 {@link SessionDeletionOrchestrator} 在同一应用事务内删除全部 归属
   * Session（Work/Invocation/Command/Thread/Entry/Session 与 relation、blob 引用对账）→ CAS 删除 Chat 行。 绝不依赖
   * ON DELETE CASCADE 旁路 blob 引用计数。
   */
  @Transactional
  public void deleteChat(String id, String expectedVersion) {
    long expected = CatalogVersions.parse(expectedVersion, "expectedVersion");
    UUID parsed = ChatIds.parseUuid(id, "id");
    Chat locked = repository.lockById(parsed);
    if (locked == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    ensureExpectedVersion(locked, id, expectedVersion, expected);
    sessionDeletionService.deleteSessionsByOwner(new OwnerRef.Chat(locked.getId()));
    if (!repository.deleteById(locked.getId(), expected)) {
      throw new IllegalStateException("chat " + id + " changed under lock");
    }
  }

  private static ChatDTO convert(Chat chat) {
    if (chat == null) {
      return null;
    }
    ChatDTO dto = new ChatDTO();
    dto.setId(ChatIds.format(chat.getId()));
    dto.setTitle(chat.getTitle());
    dto.setAgentName(chat.getAgentName());
    dto.setEnvironmentName(chat.getEnvironmentName());
    dto.setYoloEnabled(chat.isYoloEnabled());
    dto.setVersion(CatalogVersions.format(chat.getVersion()));
    dto.setCreateTime(chat.getCreateTime());
    dto.setUpdateTime(chat.getUpdateTime());
    return dto;
  }

  private static void ensureExpectedVersion(
      Chat chat, String id, String expectedVersion, long expected) {
    if (chat.getVersion() != expected) {
      throw new AiVersionConflictException(
          RESOURCE, expectedVersion, CatalogVersions.format(chat.getVersion()));
    }
  }
}
