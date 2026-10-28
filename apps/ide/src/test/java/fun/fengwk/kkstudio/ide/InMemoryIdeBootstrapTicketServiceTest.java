package fun.fengwk.kkstudio.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;

/** 意图：bootstrap 票据绑定 Daemon 实例 + view + epoch + workspace，且按实例或视图精确撤销，不误伤其它代际/视图。 */
class InMemoryIdeBootstrapTicketServiceTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final DaemonConnectionIdentity REPLACED =
      new DaemonConnectionIdentity(ENV, "33333333-3333-3333-3333-333333333333");
  private static final DaemonConnectionIdentity CURRENT =
      new DaemonConnectionIdentity(ENV, "44444444-4444-4444-4444-444444444444");
  private static final String VIEW_1 = "11111111-2222-3333-4444-555555555555";
  private static final String VIEW_2 = "66666666-7777-8888-9999-aaaaaaaaaaaa";

  @Test
  void revokeClearsOnlyMatchingGeneration() {
    InMemoryIdeBootstrapTicketService service = new InMemoryIdeBootstrapTicketService();
    String oldTicket = service.issue(REPLACED, VIEW_1, 1L, "/ws/1");
    String newTicket = service.issue(CURRENT, VIEW_2, 2L, "/ws/2");

    service.revoke(REPLACED);

    assertTrue(service.consume(oldTicket).isEmpty());
    IdeBootstrapTicketService.Ticket surviving = service.consume(newTicket).orElseThrow();
    assertEquals(CURRENT, surviving.identity());
    assertEquals(VIEW_2, surviving.viewId());
    assertEquals(2L, surviving.bindingEpoch());
    assertEquals("/ws/2", surviving.workspacePath());
  }

  @Test
  void revokeViewClearsOnlyThatViewForTheEnvironment() {
    InMemoryIdeBootstrapTicketService service = new InMemoryIdeBootstrapTicketService();
    String first = service.issue(CURRENT, VIEW_1, 1L, "/ws/1");
    String second = service.issue(CURRENT, VIEW_2, 2L, "/ws/2");

    service.revokeView(ENV, VIEW_1);

    assertTrue(service.consume(first).isEmpty());
    assertTrue(service.consume(second).isPresent());
  }
}
