package fun.fengwk.kkstudio.platform.orchestration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.canvas.CanvasSessionRepository;
import fun.fengwk.kkstudio.canvas.CanvasStore;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.history.CustomMessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.SystemReminder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextClassifier;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.platform.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSessionRepository;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentSessionOwnershipRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentSessionRepository;
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
 * 产品 owner 共用的 Harness Session 查询用例。
 *
 * <p>owner 关系只负责枚举 Session，Session/Thread 的事实统一从 {@link HarnessRuntime} 读取（Session 名称与创建时间来自
 * durable {@code Session}，Thread 名称来自 durable {@code ThreadState}）；不引入 Session title 或 Thread
 * status 的冗余持久化字段。
 */
@Service
public class HarnessOwnerQueryService {

  private static final ThreadContextClassifier CONTEXT_CLASSIFIER = new ThreadContextClassifier();

  private final ChatRepository chatRepository;
  private final ChatSessionRepository chatSessionRepository;
  private final CanvasStore canvasStore;
  private final CanvasSessionRepository canvasSessionRepository;
  private final IssueAgentSessionRepository issueAgentSessionRepository;
  private final IssueAgentSessionOwnershipRepository issueAgentSessionOwnershipRepository;
  private final ObjectProvider<HarnessRuntime> runtimes;

  public HarnessOwnerQueryService(
      ChatRepository chatRepository,
      ChatSessionRepository chatSessionRepository,
      CanvasStore canvasStore,
      CanvasSessionRepository canvasSessionRepository,
      IssueAgentSessionRepository issueAgentSessionRepository,
      IssueAgentSessionOwnershipRepository issueAgentSessionOwnershipRepository,
      ObjectProvider<HarnessRuntime> runtimes) {
    this.chatRepository = Objects.requireNonNull(chatRepository, "chatRepository");
    this.chatSessionRepository =
        Objects.requireNonNull(chatSessionRepository, "chatSessionRepository");
    this.canvasStore = Objects.requireNonNull(canvasStore, "canvasStore");
    this.canvasSessionRepository =
        Objects.requireNonNull(canvasSessionRepository, "canvasSessionRepository");
    this.issueAgentSessionRepository =
        Objects.requireNonNull(issueAgentSessionRepository, "issueAgentSessionRepository");
    this.issueAgentSessionOwnershipRepository =
        Objects.requireNonNull(
            issueAgentSessionOwnershipRepository, "issueAgentSessionOwnershipRepository");
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
  }

  /** 返回 Chat owner 的 Session 摘要，关系顺序保持最近归属优先。 */
  public List<HarnessSessionSummaryDTO> listChatSessions(UUID chatId) {
    Objects.requireNonNull(chatId, "chatId");
    if (chatRepository.getById(chatId) == null) {
      throw new AiResourceNotFoundException("chat");
    }
    return listSessionSummaries(chatSessionRepository.listSessionIds(chatId));
  }

  /** 返回 Canvas owner 的 Session 摘要，关系顺序保持最近归属优先。 */
  public List<HarnessSessionSummaryDTO> listCanvasSessions(UUID canvasId) {
    Objects.requireNonNull(canvasId, "canvasId");
    if (canvasStore.findDocument(canvasId).isEmpty()) {
      throw new AiResourceNotFoundException("canvas");
    }
    return listSessionSummaries(canvasSessionRepository.listSessionIds(canvasId));
  }

  /** 返回某个 Issue+Agent 稳定归属持有的 Session 摘要。 */
  public List<HarnessSessionSummaryDTO> listIssueAgentSessions(UUID issueAgentSessionId) {
    Objects.requireNonNull(issueAgentSessionId, "issueAgentSessionId");
    IssueAgentSession binding = issueAgentSessionRepository.getById(issueAgentSessionId);
    if (binding == null) {
      throw new AiResourceNotFoundException("issue_agent_session");
    }
    return listSessionSummaries(
        issueAgentSessionOwnershipRepository.listSessionIds(issueAgentSessionId));
  }

  /** 统一按 owner 查询 Session 摘要。 */
  public List<HarnessSessionSummaryDTO> listSessionsByOwner(OwnerRef owner) {
    Objects.requireNonNull(owner, "owner");
    return switch (owner.type()) {
      case CHAT -> listChatSessions(owner.id());
      case CANVAS -> listCanvasSessions(owner.id());
      case ISSUE_AGENT_SESSION -> listIssueAgentSessions(owner.id());
    };
  }

  /** 返回一个 Session 的 Thread 摘要，状态与 Model 均从同一 snapshot 派生，名称来自 durable ThreadState。 */
  public List<HarnessThreadSummaryDTO> listThreadSummaries(UUID sessionId) {
    Objects.requireNonNull(sessionId, "sessionId");
    HarnessRuntime runtime = requireRuntime();
    List<ThreadState> threads = runtime.listThreadsBySession(sessionId);
    List<HarnessThreadSummaryDTO> summaries = new ArrayList<>(threads.size());
    for (ThreadState thread : threads) {
      ThreadSnapshot snapshot = runtime.getThreadSnapshot(thread.id());
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
      Session session = runtime.getSession(sessionId);
      List<Entry> entries = runtime.getSessionEntries(sessionId);
      List<ThreadState> threads = runtime.listThreadsBySession(sessionId);
      summaries.add(toSessionSummary(session, entries, threads));
    }
    return List.copyOf(summaries);
  }

  private static HarnessSessionSummaryDTO toSessionSummary(
      Session session, List<Entry> entries, List<ThreadState> threads) {
    List<Entry> orderedEntries = orderedEntries(entries);
    Instant createdAt = session.createdAt();
    Instant lastActivityAt = createdAt;
    for (Entry entry : orderedEntries) {
      lastActivityAt = max(lastActivityAt, entry.createdAt());
    }
    for (ThreadState thread : threads) {
      lastActivityAt = max(lastActivityAt, thread.updatedAt());
    }

    HarnessSessionSummaryDTO dto = new HarnessSessionSummaryDTO();
    dto.setSessionId(session.id().toString());
    dto.setName(session.name());
    dto.setCreatedAt(createdAt);
    dto.setLastActivityAt(lastActivityAt);
    dto.setFirstMessagePreview(firstMessagePreview(orderedEntries));
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
    dto.setName(thread.name());
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

  /** 只取首个非 blank USER 文本作为摘要预览；无任何 USER 文本（包括纯资源消息）时返回 null，绝不回退为名称或 id 派生值。 */
  private static String firstMessagePreview(List<Entry> entries) {
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
    return null;
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

  /**
   * head 用户可读消息/资源预览：用户可读消息 text 优先、其次资源名；无则 null。
   *
   * <p>运行时注入的 {@link SystemReminder} 是内部上下文提醒，不是用户发言，因此绝不作为用户可见预览。
   */
  private static String messagePreview(Entry entry) {
    AgentMessage message =
        switch (entry.payload()) {
          case MessagePayload value -> value.message();
          case CustomMessagePayload value -> value.message();
          default -> null;
        };
    if (message == null || SystemReminder.isReminder(message)) {
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
