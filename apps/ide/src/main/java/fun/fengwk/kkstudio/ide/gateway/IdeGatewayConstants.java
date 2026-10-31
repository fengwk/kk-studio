package fun.fengwk.kkstudio.ide.gateway;

import java.time.Duration;

/** IDE 网关通用常量。 */
public final class IdeGatewayConstants {

  public static final String SESSION_COOKIE_NAME = "kk_ide_session";
  public static final Duration DEFAULT_SESSION_TTL = Duration.ofHours(12);
  public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMinutes(5);
  public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
  public static final int MAX_HEADER_BYTES = 64 * 1024;
  public static final String ROUTE_PREFIX = "/environment-ide/";
  public static final String BOOTSTRAP_ENDPOINT = "/__ide/bootstrap";
  public static final String SESSION_ENDPOINT = "/__ide/session";

  private IdeGatewayConstants() {}
}
