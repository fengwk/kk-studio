package fun.fengwk.kkstudio.ide.gateway;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.account.StudioAccountService;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.account.StudioRole;

import java.util.Optional;
import java.util.UUID;

class IdeStudioSessionMonitorTest {

  @Test
  void onlyChecksConnectionsBetweenOpenAndClose() {
    // Failed handovers and connections that close while opening must not retain a registration.
    StudioAccountService accounts = mock(StudioAccountService.class);
    IdeStudioSessionMonitor monitor = new IdeStudioSessionMonitor(accounts);
    try {
      var listener = monitor.listener("fixture", UUID.randomUUID());
      monitor.tick();
      verifyNoInteractions(accounts);

      Connection connection = connection(true);
      listener.onOpened(connection);
      monitor.tick();
      verify(accounts).authenticate("fixture");
      listener.onClosed(connection);

      clearInvocations(accounts);
      listener.onOpened(connection(false));
      monitor.tick();
      verifyNoInteractions(accounts);
      monitor.start();
    } finally {
      monitor.stop();
    }
  }

  @Test
  void failsClosedOnUnavailableAuthenticationAndOnDifferentUser() {
    // A lookup failure closes that connection without cancelling checks of other connections.
    StudioAccountService accounts = mock(StudioAccountService.class);
    IdeStudioSessionMonitor monitor = new IdeStudioSessionMonitor(accounts);
    UUID userId = UUID.randomUUID();
    Connection unavailable = connection(true);
    Connection changed = connection(true);
    when(accounts.authenticate("unavailable")).thenThrow(new IllegalStateException());
    when(accounts.authenticate("changed"))
        .thenReturn(Optional.of(new StudioPrincipal(UUID.randomUUID(), "other", StudioRole.USER)));
    monitor.listener("unavailable", userId).onOpened(unavailable);
    monitor.listener("changed", userId).onOpened(changed);

    monitor.tick();

    verify(unavailable).close();
    verify(changed).close();
  }

  private static Connection connection(boolean open) {
    Connection connection = mock(Connection.class);
    EndPoint endPoint = mock(EndPoint.class);
    when(connection.getEndPoint()).thenReturn(endPoint);
    when(endPoint.isOpen()).thenReturn(open);
    return connection;
  }
}
