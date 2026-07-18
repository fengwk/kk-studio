package fun.fengwk.kkstudio.core.harness.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import fun.fengwk.kkstudio.core.harness.thread.service.HarnessThreadCommandService;
import fun.fengwk.kkstudio.core.harness.thread.service.HarnessThreadQueryService;
import fun.fengwk.kkstudio.share.model.HarnessThreadAgentSetDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadYoloSetDTO;

import java.util.List;

/**
 * 全局 Thread 列表（含 sessionTitle）与 SET_YOLO / SET_AGENT 入队语义。
 *
 * <p>设置仅写入 ThreadInput 队列，不立即改 Thread 状态；由 Processor 在 turn 边界应用。
 */
@SpringBootTest
class HarnessThreadApiServiceTest {

  @Autowired private HarnessThreadCommandService commandService;
  @Autowired private HarnessThreadQueryService queryService;

  @Test
  void listAllIncludesSessionTitleNewestFirstAndSettingsAreQueued() {
    HarnessThreadCreateDTO create = new HarnessThreadCreateDTO();
    create.setAgentDefinitionId("1");
    create.setTitle("api-list-title");
    HarnessThreadDTO created = commandService.createThread(create);
    assertNotNull(created.getThreadId());

    HarnessThreadDTO got = queryService.getThread(created.getThreadId());
    assertEquals("api-list-title", got.getSessionTitle());
    assertEquals(created.getSessionId(), got.getSessionId());

    List<HarnessThreadDTO> all = queryService.listAll();
    assertFalse(all.isEmpty());
    assertTrue(
        all.stream().anyMatch(t -> created.getThreadId().equals(t.getThreadId())),
        "created thread visible in global list");
    HarnessThreadDTO listed =
        all.stream()
            .filter(t -> created.getThreadId().equals(t.getThreadId()))
            .findFirst()
            .orElseThrow();
    assertEquals("api-list-title", listed.getSessionTitle());

    // newest first: first element should be at least as new as our thread
    assertTrue(
        !all.get(0).getUpdateTime().isBefore(listed.getUpdateTime())
            || all.get(0).getThreadId().equals(listed.getThreadId()));

    boolean beforeYolo = Boolean.TRUE.equals(got.getYoloEnabled());
    HarnessThreadYoloSetDTO yolo = new HarnessThreadYoloSetDTO();
    yolo.setYoloEnabled(!beforeYolo);
    HarnessThreadInputDTO yoloInput = commandService.queueYolo(created.getThreadId(), yolo);
    assertEquals("set_yolo", yoloInput.getInputType());
    assertNotNull(yoloInput.getInputId());
    // Thread state not updated until processor applies the input.
    assertEquals(beforeYolo, queryService.getThread(created.getThreadId()).getYoloEnabled());

    HarnessThreadAgentSetDTO agent = new HarnessThreadAgentSetDTO();
    agent.setAgentDefinitionId("1");
    HarnessThreadInputDTO agentInput = commandService.queueAgent(created.getThreadId(), agent);
    assertEquals("set_agent", agentInput.getInputType());
    assertTrue(agentInput.getSequence() > yoloInput.getSequence());

    List<HarnessThreadInputDTO> inputs = queryService.listInputs(created.getThreadId());
    assertTrue(inputs.stream().anyMatch(i -> "set_yolo".equals(i.getInputType())));
    assertTrue(inputs.stream().anyMatch(i -> "set_agent".equals(i.getInputType())));
  }
}
