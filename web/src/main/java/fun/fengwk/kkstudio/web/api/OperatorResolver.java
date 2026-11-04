package fun.fengwk.kkstudio.web.api;

import jakarta.servlet.http.HttpServletRequest;

import java.security.Principal;

/**
 * HTTP 请求操作者解析：审批与问卷回答的 actor 只取自部署边界建立的认证主体。
 *
 * <p>单用户部署没有认证主体时回退到固定服务端身份，绝不信任客户端传入的身份字段。
 */
public final class OperatorResolver {

  /** 单用户部署边界下没有认证主体时使用的固定操作者。 */
  public static final String LOCAL_OPERATOR = "local-user";

  private OperatorResolver() {}

  /** 返回认证主体名；没有主体时使用本地固定操作者。 */
  public static String resolve(HttpServletRequest request) {
    Principal principal = request.getUserPrincipal();
    if (principal != null) {
      String name = principal.getName();
      if (name != null && !name.isBlank()) {
        return name.strip();
      }
    }
    return LOCAL_OPERATOR;
  }
}
