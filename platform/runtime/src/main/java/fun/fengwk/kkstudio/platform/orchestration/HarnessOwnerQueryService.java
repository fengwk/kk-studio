package fun.fengwk.kkstudio.platform.orchestration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.common.json.StrictJson;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.history.CustomMessagePayload;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.SessionEntryPage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.api.session.Session;
import fun.fengwk.kkstudio.harness.api.session.SystemReminder;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.api.thread.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.spi.store.UuidOrder;
import fun.fengwk.kkstudio.platform.catalog.dto.HarnessExecutionSelectionDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessSessionSummaryDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadSummaryDTO;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 通过 owner SPI 定位 Session，并从 Harness Runtime 投影 Session 和 Thread 摘要。 */
@Service
public class HarnessOwnerQueryService {

  private final List<SessionOwnership> ownerships;
  private final ObjectProvider<HarnessRuntime> runtimes;

  public HarnessOwnerQueryService(
      List<SessionOwnership> ownerships, ObjectProvider<HarnessRuntime> runtimes) {
    this.ownerships = List.copyOf(ownerships);
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
  }

  /** 按 owner SPI 返回 Session 摘要，保持 SPI 返回的顺序。 */
  public List<HarnessSessionSummaryDTO> listSessions(SessionOwner owner) {
    Objects.requireNonNull(owner, "owner");
    List<SessionOwnership> matches =
        ownerships.stream().filter(ownership -> owner.kind().equals(ownership.kind())).toList();
    if (matches.isEmpty()) {
      throw DomainException.validation(
          "owner", "Unknown or ambiguous session owner kind: " + owner.kind());
    }
    if (matches.size() != 1) {
      throw new IllegalStateException("Ambiguous session owner kind: " + owner.kind());
    }
    return listSessionSummaries(matches.getFirst().listSessions(owner));
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

  /** 按存储创建顺序返回一页 Session Entry；Entry DTO 的 wire 编码由 web runtime mapper 负责。 */
  public SessionEntryPage listSessionEntries(UUID sessionId, UUID afterEntryId, int limit) {
    Objects.requireNonNull(sessionId, "sessionId");
    return requireRuntime().listSessionEntryPage(sessionId, afterEntryId, limit);
  }

  /**
   * 按给定 Session id 投影摘要，保持输入顺序。
   *
   * <p>供 owner 侧已按自身 keyset 分页的读取使用：平台只负责从 Harness Runtime 读取 Session 事实，分页与归属仍由产品决定。
   */
  public List<HarnessSessionSummaryDTO> listSessionSummaries(List<UUID> sessionIds) {
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

  private HarnessThreadSummaryDTO toThreadSummary(ThreadSnapshot snapshot) {
    ThreadState thread = snapshot.thread();
    HarnessThreadSummaryDTO dto = new HarnessThreadSummaryDTO();
    dto.setThreadId(thread.id().toString());
    dto.setKind(thread.kind());
    dto.setParentThreadId(
        thread.parentThreadId() == null ? null : thread.parentThreadId().toString());
    dto.setName(thread.name());
    dto.setCreatedAt(thread.createdAt());
    dto.setUpdatedAt(thread.updatedAt());
    // 本地执行状态直接复用 Thread 快照四态投影，全部暴露 executionControl，删 processing。
    ThreadRuntimeStatus status = snapshot.runtimeStatus();
    dto.setStatus(status);
    dto.setExecutionControl(thread.executionControl());
    ExecutionSelection execution = snapshot.entryState().baseSettings().execution();
    if (execution != null) {
      HarnessExecutionSelectionDTO executionDto = new HarnessExecutionSelectionDTO();
      executionDto.setDriver(execution.driver());
      executionDto.setSettings(
          StrictJson.standard()
              .read(execution.settingsJson(), JsonNode.class, "execution.settings"));
      dto.setExecution(executionDto);
    }
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
    List<Entry> entries = snapshot.requireFullPath().entries();
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

  /**
   * 摘要只读取时间事实与首条 USER 文本，不依赖树的父先于子关系；保留既有 {@code (createdAt, id)} tie-break， 避免改变 Chat
   * 列表在毫秒同刻消息上的既有预览顺序。
   */
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
