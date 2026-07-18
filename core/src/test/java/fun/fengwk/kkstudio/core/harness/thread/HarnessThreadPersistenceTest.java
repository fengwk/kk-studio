package fun.fengwk.kkstudio.core.harness.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.harness.session.support.HarnessIds;
import fun.fengwk.kkstudio.core.harness.thread.service.HarnessThreadCommandService;
import fun.fengwk.kkstudio.core.harness.thread.service.HarnessThreadQueryService;
import fun.fengwk.kkstudio.core.harness.thread.store.MysqlHarnessThreadStore;
import fun.fengwk.kkstudio.harness.runtime.thread.AgentThread;
import fun.fengwk.kkstudio.share.model.HarnessThreadCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadMessageCreateDTO;
import fun.fengwk.kkstudio.share.model.ThreadEventDTO;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Deterministic H2 integration for Thread foundation: create/fork, queue idempotency, token
 * fencing, event query. Uses seeded agent_definition id=1 from data-dev.sql.
 */
@SpringBootTest
@Transactional
class HarnessThreadPersistenceTest {

  @Autowired private HarnessThreadCommandService commandService;
  @Autowired private HarnessThreadQueryService queryService;
  @Autowired private MysqlHarnessThreadStore threadStore;

  @Test
  void createRootThreadForkCursorAndQueueIdempotency() {
    HarnessThreadCreateDTO create = new HarnessThreadCreateDTO();
    create.setAgentDefinitionId("1");
    create.setTitle("phase1-thread");
    HarnessThreadDTO root = commandService.createThread(create);
    assertNotNull(root.getThreadId());
    assertNotNull(root.getSessionId());
    assertNotNull(root.getHeadEntryId());
    assertEquals("1", root.getAgentDefinitionId());

    List<ThreadEventDTO> started = queryService.listEvents(root.getThreadId(), 0, 50);
    assertFalse(started.isEmpty());
    assertTrue(
        started.stream().anyMatch(e -> "thread_started".equals(e.getEventType())),
        "root create must append THREAD_STARTED");

    HarnessThreadCreateDTO fork = new HarnessThreadCreateDTO();
    fork.setSessionId(root.getSessionId());
    fork.setFromEntryId(root.getHeadEntryId());
    fork.setAgentDefinitionId(root.getAgentDefinitionId());
    HarnessThreadDTO branch = commandService.createThread(fork);
    assertEquals(root.getSessionId(), branch.getSessionId());
    assertEquals(root.getHeadEntryId(), branch.getHeadEntryId());
    assertNotEquals(root.getThreadId(), branch.getThreadId());

    String cid = UUID.randomUUID().toString();
    HarnessThreadMessageCreateDTO msg = new HarnessThreadMessageCreateDTO();
    msg.setContent("hello-queue");
    msg.setClientMessageId(cid);
    HarnessThreadInputDTO first = commandService.submitUserMessage(root.getThreadId(), msg);
    HarnessThreadInputDTO second = commandService.submitUserMessage(root.getThreadId(), msg);
    assertEquals(first.getInputId(), second.getInputId());
    assertEquals(first.getSequence(), second.getSequence());
    assertEquals(1L, first.getSequence());

    msg.setClientMessageId(UUID.randomUUID().toString());
    msg.setContent("second-message");
    HarnessThreadInputDTO third = commandService.submitUserMessage(root.getThreadId(), msg);
    assertEquals(2L, third.getSequence());

    List<HarnessThreadInputDTO> inputs = queryService.listInputs(root.getThreadId());
    assertEquals(2, inputs.size());
    assertEquals(1L, inputs.get(0).getSequence());
    assertEquals(2L, inputs.get(1).getSequence());
  }

  @Test
  void sameThreadProcessorTokenFencingAcrossAcquire() {
    HarnessThreadCreateDTO create = new HarnessThreadCreateDTO();
    create.setAgentDefinitionId("1");
    create.setTitle("fencing");
    HarnessThreadDTO root = commandService.createThread(create);
    long threadId = HarnessIds.parsePositive(root.getThreadId(), "threadId");
    Instant now = Instant.now();

    Optional<AgentThread> first =
        threadStore.tryAcquire(threadId, "owner-a", now, Duration.ofSeconds(30));
    Optional<AgentThread> second =
        threadStore.tryAcquire(threadId, "owner-b", now, Duration.ofSeconds(30));
    assertTrue(first.isPresent());
    assertFalse(second.isPresent());
    assertEquals("owner-a", first.orElseThrow().processorToken());

    assertTrue(threadStore.release(threadId, "owner-a", now.plusSeconds(1)));
    Optional<AgentThread> third =
        threadStore.tryAcquire(threadId, "owner-b", now.plusSeconds(2), Duration.ofSeconds(30));
    assertTrue(third.isPresent());
    assertEquals("owner-b", third.orElseThrow().processorToken());
  }
}
