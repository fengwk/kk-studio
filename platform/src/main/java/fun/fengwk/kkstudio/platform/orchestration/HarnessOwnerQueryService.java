package fun.fengwk.kkstudio.platform.orchestration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.canvas.CanvasSessionRepository;
import fun.fengwk.kkstudio.canvas.CanvasStore;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.history.CustomMessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryType;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextClassifier;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.platform.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSessionRepository;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessModelSelectionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionSummaryDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSummaryDTO;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Chat/Canvas 共用的 Harness Session 查询用例。
 *
 * <p>owner 关系只负责枚举 Session，Session/Entry/Thread 的事实统一从 {@link HarnessRuntime} 读取；不引入 Session title
 * 或 Thread status 的冗余持久化字段。
 */
@Service
public class HarnessOwnerQueryService {

  private static final ThreadContextClassifier CONTEXT_CLASSIFIER = new ThreadContextClassifier();

  private final ChatRepository chatRepository;
  private final ChatSessionRepository chatSessionRepository;
  private final CanvasStore canvasStore;
  private final CanvasSessionRepository canvasSessionRepository;
  private final ObjectProvider<HarnessRuntime> runtimes;

  public HarnessOwnerQueryService(
      ChatRepository chatRepository,
      ChatSessionRepository chatSessionRepository,
      CanvasStore canvasStore,
      CanvasSessionRepository canvasSessionRepository,
      ObjectProvider<HarnessRuntime> runtimes) {
    this.chatRepository = Objects.requireNonNull(chatRepository, "chatRepository");
    this.chatSessionRepository =
        Objects.requireNonNull(chatSessionRepository, "chatSessionRepository");
    this.canvasStore = Objects.requireNonNull(canvasStore, "canvasStore");
    this.canvasSessionRepository =
        Objects.requireNonNull(canvasSessionRepository, "canvasSessionRepository");
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
  }

  /** 返回 Chat owner 的 Session 摘要，关系顺序保持最近归属优先。 */
  public List<HarnessSessionSummaryDTO> listChatSessions(UUID chatId) {
    Objects.requireNonNull(chatId, "chatId");
    if (chatRepository.getById(chatId) == null) {
      throw new AiResourceNotFoundException("chat", "chat not found: " + chatId);
    }
    return listSessionSummaries(chatSessionRepository.listSessionIds(chatId));
  }

  /** 返回 Canvas owner 的 Session 摘要，关系顺序保持最近归属优先。 */
  public List<HarnessSessionSummaryDTO> listCanvasSessions(UUID canvasId) {
    Objects.requireNonNull(canvasId, "canvasId");
    if (canvasStore.findDocument(canvasId).isEmpty()) {
      throw new AiResourceNotFoundException("canvas", "canvas not found: " + canvasId);
    }
    return listSessionSummaries(canvasSessionRepository.listSessionIds(canvasId));
  }

  /** 返回一个 Session 的 Thread 摘要，状态与 Model 均从同一 snapshot 派生。 */
  public List<HarnessThreadSummaryDTO> listThreadSummaries(UUID sessionId) {
    Objects.requireNonNull(sessionId, "sessionId");
    HarnessRuntime runtime = requireRuntime();
    List<ThreadState> threads = runtime.listThreadsBySession(sessionId);
    List<HarnessThreadSummaryDTO> summaries = new ArrayList<>(threads.size());
    for (ThreadState ignored : threads) {
      ThreadSnapshot snapshot = runtime.getThreadSnapshot(ignored.id());
      summaries.add(toThreadSummary(snapshot));
    }
    return List.copyOf(summaries);
  }

  /** 返回完整 Session Entry tree；Entry DTO 的 wire 编码由 web runtime mapper 负责。 */
  public List<Entry> listSessionEntries(UUID sessionId) {
    Objects.requireNonNull(sessionId, "sessionId");
    return List.copyOf(requireRuntime().getSessionEntries(sessionId));
  }

  private List<HarnessSessionSummaryDTO> listSessionSummaries(List<UUID> sessionIds) {
    Objects.requireNonNull(sessionIds, "sessionIds");
    List<HarnessSessionSummaryDTO> summaries = new ArrayList<>(sessionIds.size());
    HarnessRuntime runtime = requireRuntime();
    for (UUID sessionId : sessionIds) {
      Objects.requireNonNull(sessionId, "sessionIds[]");
      List<Entry> entries = runtime.getSessionEntries(sessionId);
      List<ThreadState> threads = runtime.listThreadsBySession(sessionId);
      summaries.add(toSessionSummary(sessionId, entries, threads));
    }
    return List.copyOf(summaries);
  }

  private static HarnessSessionSummaryDTO toSessionSummary(
      UUID sessionId, List<Entry> entries, List<ThreadState> threads) {
    List<Entry> orderedEntries = orderedEntries(entries);
    Instant createdAt = sessionCreatedAt(sessionId, orderedEntries);
    Instant lastActivityAt = createdAt;
    for (Entry entry : orderedEntries) {
      lastActivityAt = max(lastActivityAt, entry.createdAt());
    }
    for (ThreadState thread : threads) {
      lastActivityAt = max(lastActivityAt, thread.updatedAt());
    }

    HarnessSessionSummaryDTO dto = new HarnessSessionSummaryDTO();
    dto.setSessionId(sessionId.toString());
    dto.setCreatedAt(createdAt);
    dto.setLastActivityAt(lastActivityAt);
    dto.setFirstMessagePreview(firstMessagePreview(orderedEntries, sessionId));
    dto.setThreadCount(threads.size());
    return dto;
  }

  private static HarnessThreadSummaryDTO toThreadSummary(ThreadSnapshot snapshot) {
    ThreadState thread = snapshot.thread();
    ThreadContext context =
        CONTEXT_CLASSIFIER.classify(
            thread, snapshot.entryPath(), snapshot.model(), snapshot.toolSiblings());
    HarnessThreadSummaryDTO dto = new HarnessThreadSummaryDTO();
    dto.setThreadId(thread.id().toString());
    dto.setCreatedAt(thread.createdAt());
    dto.setUpdatedAt(thread.updatedAt());
    dto.setStatus(ThreadRuntimeStatus.from(context).name());
    var selection = snapshot.entryPath().baseSettings().model();
    HarnessModelSelectionDTO model = new HarnessModelSelectionDTO();
    model.setProviderName(selection.providerName());
    model.setModelName(selection.modelName());
    model.setVariant(selection.variant());
    dto.setModel(model);
    dto.setHeadMessagePreview(headMessagePreview(snapshot));
    return dto;
  }

  private static String firstMessagePreview(List<Entry> entries, UUID sessionId) {
    for (Entry entry : entries) {
      AgentMessage message = userMessage(entry);
      if (message == null) {
        continue;
      }
      String text = firstText(message);
      if (text != null) {
        return text;
      }
    }
    for (Entry entry : entries) {
      AgentMessage message = userMessage(entry);
      if (message == null) {
        continue;
      }
      String resourceName = firstResourceName(message);
      if (resourceName != null) {
        return resourceName;
      }
    }
    return "Session " + sessionId.toString().substring(0, 8);
  }

  private static String headMessagePreview(ThreadSnapshot snapshot) {
    List<Entry> entries = snapshot.entryPath().entries();
    for (int index = entries.size() - 1; index >= 0; index--) {
      String preview = messagePreview(entries.get(index));
      if (preview != null) {
        return preview;
      }
    }
    return null;
  }

  private static String messagePreview(Entry entry) {
    AgentMessage message =
        switch (entry.payload()) {
          case MessagePayload value -> value.message();
          case CustomMessagePayload value -> value.message();
          default -> null;
        };
    if (message == null) {
      return null;
    }
    if (message.role() == AgentMessageRole.SYSTEM) {
      return null;
    }
    String text = firstText(message);
    return text == null ? firstResourceName(message) : text;
  }

  private static AgentMessage userMessage(Entry entry) {
    AgentMessage message =
        switch (entry.payload()) {
          case MessagePayload value -> value.message();
          case CustomMessagePayload value -> value.message();
          default -> null;
        };
    return message != null && message.role() == AgentMessageRole.USER ? message : null;
  }

  private static String firstText(AgentMessage message) {
    for (AgentMessageContent content : message.contents()) {
      if (content instanceof TextMessageContent text && !text.text().isBlank()) {
        return text.text();
      }
    }
    return null;
  }

  private static String firstResourceName(AgentMessage message) {
    for (AgentMessageContent content : message.contents()) {
      if (content instanceof ResourceMessageContent resource) {
        return resource.name();
      }
    }
    return null;
  }

  private static List<Entry> orderedEntries(List<Entry> entries) {
    Objects.requireNonNull(entries, "entries");
    return entries.stream()
        .sorted(
            Comparator.comparing(Entry::createdAt).thenComparing(Entry::id, UuidOrder.COMPARATOR))
        .toList();
  }

  private static Instant sessionCreatedAt(UUID sessionId, List<Entry> entries) {
    return entries.stream()
        .filter(entry -> entry.payload().type() == EntryType.ROOT)
        .map(Entry::createdAt)
        .findFirst()
        .orElseThrow(
            () -> new IllegalStateException("session " + sessionId + " does not contain a ROOT"));
  }

  private static Instant max(Instant left, Instant right) {
    return left.compareTo(right) >= 0 ? left : right;
  }

  private HarnessRuntime requireRuntime() {
    HarnessRuntime runtime = runtimes.getIfAvailable();
    if (runtime == null) {
      throw new IllegalStateException("harness runtime is not available in this deployment");
    }
    return runtime;
  }
}
