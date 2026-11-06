package fun.fengwk.kkstudio.chat;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.chat.dto.ChatCreateDTO;
import fun.fengwk.kkstudio.chat.dto.ChatDTO;
import fun.fengwk.kkstudio.chat.dto.ChatPageDTO;
import fun.fengwk.kkstudio.chat.dto.ChatSessionPageDTO;
import fun.fengwk.kkstudio.chat.dto.ChatUpdateDTO;
import fun.fengwk.kkstudio.chat.dto.ChatYoloUpdateDTO;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.platform.orchestration.HarnessOwnerQueryService;
import fun.fengwk.kkstudio.platform.orchestration.SessionDeletionOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwner;
import fun.fengwk.kkstudio.share.runtime.HarnessSessionSummaryDTO;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Chat 用例：对话 CRUD 与 keyset 分页、Chat 归属 Session 的分页摘要，以及经共享 {@link SessionDeletionOrchestrator} 的深删除。
 */
@Service
public class ChatService {

  private static final String RESOURCE = "chat";
  private static final String CHAT_SESSION_RESOURCE = "chat_session";

  private final ChatRepository repository;
  private final ChatSessionRepository sessions;
  private final ChatMutationFactory mutationFactory;
  private final ChatGuard guard;
  private final HarnessOwnerQueryService harnessQueryService;
  private final SessionDeletionOrchestrator sessionDeletionService;

  public ChatService(
      ChatRepository repository,
      ChatSessionRepository sessions,
      ChatMutationFactory mutationFactory,
      ChatGuard guard,
      HarnessOwnerQueryService harnessQueryService,
      SessionDeletionOrchestrator sessionDeletionService) {
    this.repository = repository;
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.mutationFactory = mutationFactory;
    this.guard = guard;
    this.harnessQueryService = Objects.requireNonNull(harnessQueryService, "harnessQueryService");
    this.sessionDeletionService = Objects.requireNonNull(sessionDeletionService);
  }

  /** 一页对话，按最近更新倒序稳定推进；{@code nextCursor} 非空表示还有下一页。 */
  public ChatPageDTO listChats(String cursor, int limit) {
    validateLimit(limit);
    ChatCursor parsed;
    try {
      parsed = ChatCursor.parse(cursor);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation(RESOURCE, error.getMessage());
    }
    List<Chat> rows = repository.listPage(parsed, limit + 1);
    boolean hasMore = rows.size() > limit;
    List<Chat> page = hasMore ? rows.subList(0, limit) : rows;
    ChatPageDTO result = new ChatPageDTO();
    result.setItems(page.stream().map(ChatService::convert).toList());
    result.setNextCursor(hasMore ? ChatCursor.of(page.getLast()).encode() : null);
    return result;
  }

  /** 一页 Chat 归属 Session 摘要，按归属时间从新到旧稳定推进。 */
  public ChatSessionPageDTO listSessions(String chatId, String cursor, int limit) {
    UUID id = Uuids.parseCanonical(chatId, "chatId");
    validateLimit(limit);
    ChatSessionCursor parsed;
    try {
      parsed = ChatSessionCursor.parse(cursor);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation(RESOURCE, error.getMessage());
    }
    guard.requireChat(id);
    List<ChatSessionRow> rows = sessions.listPage(id, parsed, limit + 1);
    boolean hasMore = rows.size() > limit;
    List<ChatSessionRow> page = hasMore ? rows.subList(0, limit) : rows;
    ChatSessionPageDTO result = new ChatSessionPageDTO();
    result.setItems(
        harnessQueryService.listSessionSummaries(
            page.stream().map(ChatSessionRow::sessionId).toList()));
    result.setNextCursor(hasMore ? ChatSessionCursor.of(page.getLast()).encode() : null);
    return result;
  }

  /** 单个归属 Session 的摘要；Session 不存在或不属于该 Chat 时 404。 */
  public HarnessSessionSummaryDTO getSession(String chatId, String sessionId) {
    UUID chat = Uuids.parseCanonical(chatId, "chatId");
    UUID session = Uuids.parseCanonical(sessionId, "sessionId");
    ChatSession relation = sessions.findBySessionId(session);
    if (relation == null || !relation.chatId().equals(chat)) {
      throw DomainException.notFound(CHAT_SESSION_RESOURCE);
    }
    return harnessQueryService.listSessionSummaries(List.of(session)).getFirst();
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
      throw DomainException.validation(RESOURCE, "expectedVersion is required");
    }
    long expected = CatalogVersions.parse(rawExpected, "expectedVersion");
    Chat existing = guard.requireChat(id);
    ensureExpectedVersion(existing, id, rawExpected, expected);
    String previousAgentName = existing.getAgentName();
    String previousEnvironmentName = existing.getEnvironmentName();
    mutationFactory.apply(existing, updateDTO);
    if (!Objects.equals(previousAgentName, existing.getAgentName())) {
      guard.ensureAgentExists(existing.getAgentName());
    }
    if (!Objects.equals(previousEnvironmentName, existing.getEnvironmentName())) {
      guard.ensureEnvironmentExists(existing.getEnvironmentName());
    }
    return persistUpdate(existing, rawExpected, expected);
  }

  /** YOLO 是独立子资源，但与 Chat 其他修改共享同一个版本。 */
  @Transactional
  public ChatDTO updateYolo(String id, ChatYoloUpdateDTO updateDTO) {
    String rawExpected = updateDTO.getExpectedVersion();
    long expected = CatalogVersions.parse(rawExpected, "expectedVersion");
    Chat existing = guard.requireChat(id);
    ensureExpectedVersion(existing, id, rawExpected, expected);
    existing.setYoloEnabled(updateDTO.isYoloEnabled());
    return persistUpdate(existing, rawExpected, expected);
  }

  private ChatDTO persistUpdate(Chat existing, String rawExpected, long expected) {
    if (!repository.updateById(existing, expected)) {
      Chat reread = repository.getById(existing.getId());
      if (reread == null) {
        throw DomainException.notFound(RESOURCE);
      }
      throw DomainException.versionConflict(
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
    UUID parsed = Uuids.parseCanonical(id, "id");
    Chat locked = repository.lockById(parsed);
    if (locked == null) {
      throw DomainException.notFound(RESOURCE);
    }
    ensureExpectedVersion(locked, id, expectedVersion, expected);
    sessionDeletionService.deleteSessionsByOwner(
        new SessionOwner("CHAT", locked.getId().toString()));
    if (!repository.deleteById(locked.getId(), expected)) {
      throw new IllegalStateException("chat " + id + " changed under lock");
    }
  }

  private static ChatDTO convert(Chat chat) {
    if (chat == null) {
      return null;
    }
    ChatDTO dto = new ChatDTO();
    dto.setId(chat.getId().toString());
    dto.setTitle(chat.getTitle());
    dto.setAgentName(chat.getAgentName());
    dto.setEnvironmentName(chat.getEnvironmentName());
    dto.setYoloEnabled(chat.isYoloEnabled());
    dto.setVersion(CatalogVersions.format(chat.getVersion()));
    dto.setCreatedAt(chat.getCreateTime());
    dto.setUpdatedAt(chat.getUpdateTime());
    return dto;
  }

  private static void validateLimit(int limit) {
    if (limit < 1 || limit > 100) {
      throw DomainException.validation(RESOURCE, "limit must be within [1, 100]");
    }
  }

  private static void ensureExpectedVersion(
      Chat chat, String id, String expectedVersion, long expected) {
    if (chat.getVersion() != expected) {
      throw DomainException.versionConflict(
          RESOURCE, expectedVersion, CatalogVersions.format(chat.getVersion()));
    }
  }
}
