package fun.fengwk.kkstudio.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;

/** 意图：bootstrap 票据按完整绑定精确撤销；晚到的旧清理和 older-than 清理都不能删除新 epoch 票据。 */
class IdeBootstrapTicketServiceTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final EnvironmentId OTHER_ENV =
      EnvironmentId.parse("22222222-2222-2222-2222-222222222222");
  private static final DaemonConnectionIdentity CURRENT =
      new DaemonConnectionIdentity(ENV, "44444444-4444-4444-4444-444444444444");
  private static final DaemonConnectionIdentity REPLACEMENT =
      new DaemonConnectionIdentity(ENV, "66666666-6666-6666-6666-666666666666");
  private static final DaemonConnectionIdentity OTHER =
      new DaemonConnectionIdentity(OTHER_ENV, "55555555-5555-5555-5555-555555555555");
  private static final String VIEW_1 = "11111111-2222-3333-4444-555555555555";
  private static final String VIEW_2 = "66666666-7777-8888-9999-aaaaaaaaaaaa";

  @Test
  void revokeOnlyExactViewInstanceAndEpoch() {
    IdeBootstrapTicketService service = new IdeBootstrapTicketService();
    String first = service.issue(CURRENT, VIEW_1, 1L, "/ws/1");
    String nextEpoch = service.issue(CURRENT, VIEW_1, 2L, "/ws/1");
    String second = service.issue(CURRENT, VIEW_2, 2L, "/ws/2");
    String otherEnvSameView = service.issue(OTHER, VIEW_1, 1L, "/ws/other");
    String replacementSameView = service.issue(REPLACEMENT, VIEW_1, 1L, "/ws/1");

    service.revoke(CURRENT, VIEW_1, 1L);

    assertTrue(service.consume(first).isEmpty());
    assertTrue(service.consume(nextEpoch).isPresent());
    IdeBootstrapTicketService.Ticket surviving = service.consume(second).orElseThrow();
    assertEquals(CURRENT, surviving.identity());
    assertEquals(VIEW_2, surviving.viewId());
    assertEquals(2L, surviving.bindingEpoch());
    assertEquals("/ws/2", surviving.workspacePath());
    assertTrue(service.consume(otherEnvSameView).isPresent());
    // 旧实例迟到的撤销只作用于自己的票据，新实例复用同 view 的票据仍然有效。
    assertTrue(service.consume(replacementSameView).isPresent());
  }

  @Test
  void revokeOlderThanIsOneWayAndKeepsCurrentAndNewerEpochs() {
    IdeBootstrapTicketService service = new IdeBootstrapTicketService();
    String old = service.issue(CURRENT, VIEW_1, 1L, "/ws/1");
    String current = service.issue(CURRENT, VIEW_1, 2L, "/ws/1");
    String newer = service.issue(CURRENT, VIEW_1, 3L, "/ws/1");

    service.revokeOlderThan(CURRENT, VIEW_1, 2L);

    assertTrue(service.consume(old).isEmpty());
    assertTrue(service.consume(current).isPresent());
    assertTrue(service.consume(newer).isPresent());
  }
}
