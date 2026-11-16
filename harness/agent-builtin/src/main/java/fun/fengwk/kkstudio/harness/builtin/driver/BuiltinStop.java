package fun.fengwk.kkstudio.harness.builtin.driver;

import fun.fengwk.kkstudio.harness.api.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.api.history.AssistantError;
import fun.fengwk.kkstudio.harness.api.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.EntryPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.builtin.port.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelAttemptMaterialization;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.store.StreamCheckpoint;
import fun.fengwk.kkstudio.harness.builtin.processor.ModelAttemptFailureAppender;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.spi.driver.DriverActivity;
import fun.fengwk.kkstudio.harness.spi.driver.DriverCommit;
import fun.fengwk.kkstudio.harness.spi.driver.DriverStopResult;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Stop 对 Builtin generation 的收敛：中断 activity 中的调用并以结果 Entry 关闭，或清理已 attached 的临时行。
 *
 * <p>与其它 Driver 方法一样在 Runtime 已开启的事务内执行；写入顺序为失败审计 -&gt; barrier Entry -&gt; terminal 行 attach -&gt;
 * 删除临时行，保证 barrier 一旦可见就已有可结算的 generation 事实。
 */
final class BuiltinStop {

  private static final String CANCELLED_MESSAGE = "Cancelled by user";

  private final BuiltinStore store;
  private final HarnessStore.Transaction core;

  BuiltinStop(BuiltinStore store, HarnessStore.Transaction core) {
    this.store = store;
    this.core = core;
  }

  DriverStopResult stop(EntryPath path, BuiltinState builtin, DriverCommit commit) {
    ModelInvocation model = builtin.model();
    if (builtin.activity() != DriverActivity.ACTIVE) {
      discardAttachedIfHead(model, path);
      return new DriverStopResult(null, List.of());
    }
    Instant now = commit.now();
    ModelInvocationError error =
        new ModelInvocationError(ProviderErrorKind.CANCELLED, CANCELLED_MESSAGE);
    UUID parentId =
        ModelAttemptFailureAppender.append(
            store.in(core), path.root().sessionId(), path.head().id(), model);
    UUID barrierId = commit.nextId();
    commit.insertEntry(
        new Entry(
            barrierId, path.root().sessionId(), parentId, barrier(model.streamCheckpoint()), now));
    store
        .in(core)
        .updateModelInvocation(model.cancel(error, now).attachResultEntry(barrierId, now));
    store.in(core).deleteModelInvocation(model.id());
    return new DriverStopResult(barrierId, List.of(model.id()));
  }

  /** Tool 阶段已 attached 的 generation 没有待停止的执行：只校验物化后删除临时行。 */
  void discardAttachedIfHead(ModelInvocation model, EntryPath path) {
    if (model.resultEntryId() == null || !model.resultEntryId().equals(path.head().id())) {
      return;
    }
    ModelAttemptMaterialization.validateAttached(model, path);
    store.in(core).deleteModelInvocation(model.id());
  }

  /** 有 partial 时以 AssistantAborted 固化已产出内容，否则写明确的取消错误。 */
  private static EntryPayload barrier(StreamCheckpoint checkpoint) {
    if (checkpoint == null) {
      return cancellationError();
    }
    List<AgentMessageContent> contents = new ArrayList<>(2);
    if (!checkpoint.thinking().isEmpty()) {
      contents.add(new ThinkingMessageContent(checkpoint.thinking()));
    }
    if (!checkpoint.text().isEmpty()) {
      contents.add(new TextMessageContent(checkpoint.text()));
    }
    if (contents.isEmpty()) {
      return cancellationError();
    }
    return new AssistantAbortedPayload(new AgentMessage(AgentMessageRole.ASSISTANT, contents));
  }

  private static AssistantErrorPayload cancellationError() {
    return new AssistantErrorPayload(
        new AssistantError(AssistantError.CANCELLED_CODE, CANCELLED_MESSAGE), null);
  }
}
