package fun.fengwk.kkstudio.core.studio.function.opencli;

/** OpenCLI Hub transport、wire contract 或 provider execution 的 fail-closed 错误。 */
public class OpenCliHubException extends RuntimeException {

  public OpenCliHubException(String message) {
    super(message);
  }

  public OpenCliHubException(String message, Throwable cause) {
    super(message, cause);
  }
}
