package fun.fengwk.kkstudio.core.agent.runtime.engine;

import dev.langchain4j.internal.Json;
import dev.langchain4j.model.chat.response.*;
import fun.fengwk.convention4j.springboot.starter.transaction.TransactionExecutor;
import fun.fengwk.kkstudio.core.agent.runtime.event.EventSession;
import fun.fengwk.kkstudio.core.agent.runtime.event.EventSessionStore;
import fun.fengwk.kkstudio.core.agent.runtime.event.EventStore;
import fun.fengwk.kkstudio.core.agent.runtime.event.ev.TurnStartEvent;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * @author fengwk
 */
@Slf4j
public class TurnExecutor implements StreamingChatResponseHandler {

    private final String sessionId;
    private final EventStore eventStore;
    private final EventSessionStore eventSessionStore;
    private final TransactionExecutor transactionExecutor;

    public TurnExecutor(String sessionId, EventStore eventStore, EventSessionStore eventSessionStore, TransactionExecutor transactionExecutor) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.eventSessionStore = Objects.requireNonNull(eventSessionStore, "eventSessionStore must not be null");
        this.transactionExecutor = transactionExecutor;
    }

    public boolean tryStartTurn() {
        EventSession eventSession = eventSessionStore.get(sessionId);
        if (eventSession == null) {
            // TODO log.warn 错误的 sessionId
            return false;
        }

        return transactionExecutor.executeWithRequired(() -> {
            // TODO 要测试首次构建 session headEventId 为 null 的情况 cas 是否有效
            String oldHeadEventId = eventSession.getHeadEventId();
            String newHeadEventId = eventStore.generateEventId();
            boolean lockHead = eventSessionStore.casHeadEventId(sessionId, oldHeadEventId, newHeadEventId);
            if (!lockHead) {
                // TODO log.info 并发冲突，有其它线程抢占的当前 turn
                return false;
            }

            TurnStartEvent turnStartEvent = new TurnStartEvent();
            turnStartEvent.setEventId(newHeadEventId);
            turnStartEvent.setParentEventId(oldHeadEventId);
//            turnStartEvent.setEventTreeId(eventSession.getEventTreeId());
            turnStartEvent.setCreateTime(LocalDateTime.now());
            eventStore.append(turnStartEvent);
            // TODO log.debug
            return true;
        });
    }

    @Override
    public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
        log.info("onPartialResponse, partialResponse: {}", Json.toJson(partialResponse));
    }

    @Override
    public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
        log.info("onPartialThinking, partialResponse: {}", Json.toJson(partialThinking));
    }

    @Override
    public void onPartialToolCall(PartialToolCall partialToolCall, PartialToolCallContext context) {
        log.info("onPartialToolCall, partialToolCall: {}", Json.toJson(partialToolCall));
    }

    @Override
    public void onCompleteToolCall(CompleteToolCall completeToolCall) {
        log.info("onCompleteToolCall, completeToolCall: {}", Json.toJson(completeToolCall));
    }

    @Override
    public void onCompleteResponse(ChatResponse completeResponse) {
        log.info("onCompleteToolCall, completeResponse: {}", Json.toJson(completeResponse));
    }

    @Override
    public void onError(Throwable error) {
        log.error("event error", error);
    }

}
