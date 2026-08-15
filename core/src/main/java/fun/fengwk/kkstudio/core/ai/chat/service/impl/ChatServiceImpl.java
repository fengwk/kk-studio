package fun.fengwk.kkstudio.core.ai.chat.service.impl;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.ai.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.core.ai.chat.repo.ChatThreadRepository;
import fun.fengwk.kkstudio.core.ai.chat.service.ChatIds;
import fun.fengwk.kkstudio.core.ai.chat.service.ChatService;
import fun.fengwk.kkstudio.core.ai.chat.service.converter.ChatConverter;
import fun.fengwk.kkstudio.core.ai.chat.service.model.Chat;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.ai.error.AiVersionConflictException;
import fun.fengwk.kkstudio.core.ai.error.CatalogVersions;
import fun.fengwk.kkstudio.core.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatUpdateDTO;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** Chat CRUD 服务；deleteChat 是深删除：在同一应用事务内显式删除全部关联 Harness 状态与 blob 引用。 */
@Service
public class ChatServiceImpl implements ChatService {

  private static final String RESOURCE = "chat";

  private final ChatRepository repository;
  private final ChatConverter converter;
  private final ChatMutationFactory mutationFactory;
  private final ChatGuard guard;
  private final ChatThreadRepository chatThreadRepository;
  private final ObjectProvider<HarnessStore> harnessStoreProvider;
  private final ObjectProvider<SessionBlobRefManager> refManagerProvider;

  public ChatServiceImpl(
      ChatRepository repository,
      ChatConverter converter,
      ChatMutationFactory mutationFactory,
      ChatGuard guard,
      ChatThreadRepository chatThreadRepository,
      ObjectProvider<HarnessStore> harnessStoreProvider,
      ObjectProvider<SessionBlobRefManager> refManagerProvider) {
    this.repository = repository;
    this.converter = converter;
    this.mutationFactory = mutationFactory;
    this.guard = guard;
    this.chatThreadRepository = chatThreadRepository;
    this.harnessStoreProvider = harnessStoreProvider;
    this.refManagerProvider = refManagerProvider;
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
   * 深删除：锁定 Chat 行（版本 CAS 保留）→ 枚举绑定 Thread → 在同一事务内按 FK 顺序删除
   * Work/Invocation/Command/Thread/Entry/Session（Session blob ref 逐行 release，ref_count 对账）→ 删除 Chat
   * 行。绝不依赖 ON DELETE CASCADE 旁路 blob 引用计数。
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
    List<UUID> threadIds =
        chatThreadRepository.listThreadIds(locked.getId()).stream()
            .sorted(UuidOrder.COMPARATOR)
            .collect(Collectors.toList());
    if (!threadIds.isEmpty()) {
      // Thread 只可能由 Harness Runtime 创建：无 Runtime（无 store）部署中不存在绑定 Thread；存在即不变量违反。
      HarnessStore harnessStore = harnessStoreProvider.getIfAvailable();
      if (harnessStore == null) {
        throw new IllegalStateException(
            "harness store is not available; cannot deep delete bound threads");
      }
      // store 事务（PROPAGATION_REQUIRED）加入本应用事务；blob release 经 SessionBlobRefManager（MANDATORY）。
      harnessStore.transaction(
          tx -> {
            for (UUID threadId : threadIds) {
              deleteThreadDeep(tx, threadId);
            }
            return null;
          });
    }
    // Chat 行已在本事务锁定：CAS 删除必然命中；失败即不变量违反。
    if (!repository.deleteById(locked.getId(), expected)) {
      throw new IllegalStateException("chat " + id + " changed under lock");
    }
  }

  /**
   * 深删除单个 Thread 及其 Session：Work → ToolInvocation → ModelInvocation → Command → Thread → Entry
   * （叶子优先）→ Session blob ref release → Session。Thread 行缺失（chat_thread FK 保证正常情况下不可能）视为已删除， 幂等跳过。
   */
  private void deleteThreadDeep(HarnessStore.Transaction tx, UUID threadId) {
    Objects.requireNonNull(tx, "tx");
    ThreadState thread = tx.lockThread(threadId).orElse(null);
    if (thread == null) {
      return;
    }
    UUID sessionId = tx.loadEntryPath(thread.headEntryId()).root().sessionId();
    tx.deleteWorkByThread(threadId);
    tx.deleteToolInvocations(threadId);
    tx.deleteModelInvocations(threadId);
    tx.deleteCommands(threadId);
    tx.deleteThread(threadId);
    tx.deleteEntries(sessionId);
    // Storage 未启用时不存在 blob ref 行（附件路径被拒绝）；启用时逐行 release（ref_count 对账）。
    SessionBlobRefManager refManager = refManagerProvider.getIfAvailable();
    if (refManager != null) {
      for (UUID blobId : refManager.listBlobIds(sessionId)) {
        refManager.releaseRef(sessionId, blobId);
      }
    }
    tx.deleteSession(sessionId);
  }

  private static void ensureExpectedVersion(
      Chat chat, String id, String expectedVersion, long expected) {
    if (chat.getVersion() != expected) {
      throw new AiVersionConflictException(
          RESOURCE, id, expectedVersion, CatalogVersions.format(chat.getVersion()));
    }
  }
}
