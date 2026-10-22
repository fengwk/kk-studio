package fun.fengwk.kkstudio.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;

import java.util.UUID;

/** 意图：bootstrap 票据按连接代际（Daemon 实例 + 租约）绑定；租约失效/接管时 revoke 只作废旧代际票据，绝不误伤新代际。 */
class InMemoryIdeBootstrapTicketServiceTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final UUID LEASE_OLD = new UUID(0L, 1L);
  private static final UUID LEASE_NEW = new UUID(0L, 2L);

  @Test
  void revokeClearsOnlyMatchingLeaseGeneration() {
    InMemoryIdeBootstrapTicketService service = new InMemoryIdeBootstrapTicketService();
    String oldTicket = service.issue(identity(LEASE_OLD), "view-1", "/ws/1");
    String newTicket = service.issue(identity(LEASE_NEW), "view-2", "/ws/2");

    service.revoke(ENV, LEASE_OLD);

    assertTrue(service.consume(oldTicket).isEmpty());
    IdeBootstrapTicketService.Ticket surviving = service.consume(newTicket).orElseThrow();
    assertEquals(LEASE_NEW, surviving.identity().leaseToken());
  }

  private static DaemonConnectionIdentity identity(UUID leaseToken) {
    return new DaemonConnectionIdentity(ENV, "33333333-3333-3333-3333-333333333333", leaseToken);
  }
}
