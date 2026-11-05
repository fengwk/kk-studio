package fun.fengwk.kkstudio.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;

/** 意图：bootstrap 票据绑定 Daemon 实例 + view + epoch + workspace，且按视图精确撤销，不误伤其它视图或环境。 */
class IdeBootstrapTicketServiceTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final EnvironmentId OTHER_ENV =
      EnvironmentId.parse("22222222-2222-2222-2222-222222222222");
  private static final DaemonConnectionIdentity CURRENT =
      new DaemonConnectionIdentity(ENV, "44444444-4444-4444-4444-444444444444");
  private static final DaemonConnectionIdentity OTHER =
      new DaemonConnectionIdentity(OTHER_ENV, "55555555-5555-5555-5555-555555555555");
  private static final String VIEW_1 = "11111111-2222-3333-4444-555555555555";
  private static final String VIEW_2 = "66666666-7777-8888-9999-aaaaaaaaaaaa";

  @Test
  void revokeViewClearsOnlyThatViewForTheEnvironment() {
    IdeBootstrapTicketService service = new IdeBootstrapTicketService();
    String first = service.issue(CURRENT, VIEW_1, 1L, "/ws/1");
    String second = service.issue(CURRENT, VIEW_2, 2L, "/ws/2");
    String otherEnvSameView = service.issue(OTHER, VIEW_1, 1L, "/ws/other");

    service.revokeView(ENV, VIEW_1);

    assertTrue(service.consume(first).isEmpty());
    IdeBootstrapTicketService.Ticket surviving = service.consume(second).orElseThrow();
    assertEquals(CURRENT, surviving.identity());
    assertEquals(VIEW_2, surviving.viewId());
    assertEquals(2L, surviving.bindingEpoch());
    assertEquals("/ws/2", surviving.workspacePath());
    assertTrue(service.consume(otherEnvSameView).isPresent());
  }
}
