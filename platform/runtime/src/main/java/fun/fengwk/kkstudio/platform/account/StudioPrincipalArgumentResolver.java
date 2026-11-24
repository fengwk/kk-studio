package fun.fengwk.kkstudio.platform.account;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import fun.fengwk.kkstudio.common.error.DomainException;

/** Passes the filter-authenticated account to the application explicitly. */
public final class StudioPrincipalArgumentResolver implements HandlerMethodArgumentResolver {
  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.getParameterType() == StudioPrincipal.class;
  }

  @Override
  public StudioPrincipal resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer container,
      NativeWebRequest request,
      WebDataBinderFactory binderFactory) {
    var principal =
        (StudioPrincipal)
            request.getAttribute(
                StudioBrowserSession.PRINCIPAL_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    if (principal == null) {
      throw DomainException.unauthorized("NOT_AUTHENTICATED", "Login required");
    }
    return principal;
  }
}
