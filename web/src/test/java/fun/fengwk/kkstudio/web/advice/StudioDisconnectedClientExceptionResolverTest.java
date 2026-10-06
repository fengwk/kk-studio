package fun.fengwk.kkstudio.web.advice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;

/**
 * {@link StudioDisconnectedClientExceptionResolver} 的边界单元测试。
 *
 * <p>验证只有客户端断连与已提交响应被隔离为“已处理、无 body”，普通/上游 IOException 必须返回 {@code null} 继续交给既有翻译链。
 */
class StudioDisconnectedClientExceptionResolverTest {

  private final StudioDisconnectedClientExceptionResolver resolver =
      new StudioDisconnectedClientExceptionResolver();

  private static MockHttpServletRequest request() {
    return new MockHttpServletRequest("GET", "/assets/index.js");
  }

  private ModelAndView resolve(Exception ex, MockHttpServletResponse response) {
    return resolver.resolveException(request(), response, new Object(), ex);
  }

  /** 测试意图：Tomcat ClientAbortException 被识别为断连并隔离为无 body。 */
  @Test
  void clientAbortExceptionIsHandledWithoutBody() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    ModelAndView mav =
        resolve(new ClientAbortException(new IOException("Connection reset")), response);
    assertNotNull(mav);
    assertTrue(mav.isEmpty());
    assertEquals("", response.getContentAsString());
  }

  /** 测试意图：容器特有的 “Broken pipe” 消息 IOException 也被识别为断连。 */
  @Test
  void brokenPipeIOExceptionIsHandledWithoutBody() {
    MockHttpServletResponse response = new MockHttpServletResponse();
    ModelAndView mav = resolve(new IOException("Broken pipe"), response);
    assertNotNull(mav);
    assertTrue(mav.isEmpty());
  }

  /** 测试意图：Spring 的 AsyncRequestNotUsableException 属于断连判定。 */
  @Test
  void asyncRequestNotUsableExceptionIsHandledWithoutBody() {
    MockHttpServletResponse response = new MockHttpServletResponse();
    ModelAndView mav = resolve(new AsyncRequestNotUsableException("response not usable"), response);
    assertNotNull(mav);
    assertTrue(mav.isEmpty());
  }

  /** 测试意图：上游连接问题（RestClientException）即使携带断连消息也不能当作客户端断连吞掉。 */
  @Test
  void upstreamRestClientExceptionIsNotSwallowed() {
    MockHttpServletResponse response = new MockHttpServletResponse();
    ModelAndView mav = resolve(new RestClientException("upstream Broken pipe"), response);
    assertNull(mav);
  }

  /** 测试意图：未提交响应上的非断连 IOException 必须返回 null，交由既有 Result 翻译链。 */
  @Test
  void localIOExceptionIsDelegated() {
    MockHttpServletResponse response = new MockHttpServletResponse();
    ModelAndView mav = resolve(new IOException("simulated local failure"), response);
    assertNull(mav);
  }

  /** 测试意图：已提交响应上的普通异常不产生第二次 body 写。 */
  @Test
  void committedResponseIsHandledWithoutBody() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setCommitted(true);
    ModelAndView mav = resolve(new IllegalStateException("failure after commit"), response);
    assertNotNull(mav);
    assertTrue(mav.isEmpty());
    assertEquals("", response.getContentAsString());
  }

  /** 测试意图：未提交响应上的普通异常不被本 resolver 拦截。 */
  @Test
  void uncommittedNormalExceptionIsDelegated() {
    MockHttpServletResponse response = new MockHttpServletResponse();
    ModelAndView mav = resolve(new IllegalStateException("normal failure"), response);
    assertNull(mav);
  }
}
