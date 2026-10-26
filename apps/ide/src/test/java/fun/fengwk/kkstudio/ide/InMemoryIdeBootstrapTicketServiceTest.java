package fun.fengwk.kkstudio.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;

/** 意图：bootstrap 票据按 Daemon 实例代际绑定；另一个实例接管时 revoke 只作废旧代际票据，绝不误伤新代际。 */
class InMemoryIdeBootstrapTicketServiceTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final DaemonConnectionIdentity REPLACED =
      new DaemonConnectionIdentity(ENV, "33333333-3333-3333-3333-333333333333");
  private static final DaemonConnectionIdentity CURRENT =
      new DaemonConnectionIdentity(ENV, "44444444-4444-4444-4444-444444444444");

  @Test
  void revokeClearsOnlyMatchingGeneration() {
    InMemoryIdeBootstrapTicketService service = new InMemoryIdeBootstrapTicketService();
    String oldTicket = service.issue(REPLACED, "view-1", "/ws/1");
    String newTicket = service.issue(CURRENT, "view-2", "/ws/2");

    service.revoke(REPLACED);

    assertTrue(service.consume(oldTicket).isEmpty());
    IdeBootstrapTicketService.Ticket surviving = service.consume(newTicket).orElseThrow();
    assertEquals(CURRENT, surviving.identity());
  }
}
