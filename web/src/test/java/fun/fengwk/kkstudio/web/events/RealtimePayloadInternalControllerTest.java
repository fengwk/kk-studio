package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.harness.infra.realtime.RealtimePayloadBuffer;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalPaths;
import fun.fengwk.kkstudio.platform.environment.cluster.HmacClusterInternalAuthentication;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** 内部 realtime payload 端点：认证先于读取、canonical UUID 路径、完整 JSON 返回与缺失/未知 payload 的 404。 */
class RealtimePayloadInternalControllerTest {

  private static final UUID NODE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final UUID OTHER_NODE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID THREAD = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final String SECRET = "test-cluster-secret-0123456789abcdef";
  private static final String EVENT_JSON =
      "{\"threadId\":\"11111111-1111-1111-1111-111111111111\",\"text\":\"界面\"}";

  private HmacClusterInternalAuthentication authentication;
  private RealtimePayloadBuffer buffer;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    authentication =
        new HmacClusterInternalAuthentication(
            SECRET, NODE, Clock.systemUTC(), Duration.ofSeconds(30), 1024);
    buffer = new RealtimePayloadBuffer();
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new RealtimePayloadInternalController(authentication, buffer))
            .build();
  }

  @Test
  void authenticatedRequestReturnsExactCanonicalJson() throws Exception {
    UUID payloadId = buffer.put(THREAD, EVENT_JSON).orElseThrow();
    String path = ClusterInternalPaths.realtimePayloadPath(payloadId);

    mockMvc
        .perform(
            get(path)
                .headers(headers(authentication.sign("GET", path, NODE)))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().string(EVENT_JSON));
  }

  @Test
  void missingAuthenticationIsRejectedWithoutRevealingPayload() throws Exception {
    UUID payloadId = buffer.put(THREAD, EVENT_JSON).orElseThrow();

    String body =
        mockMvc
            .perform(get(ClusterInternalPaths.realtimePayloadPath(payloadId)))
            .andExpect(status().isUnauthorized())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertTrue(!body.contains(EVENT_JSON), "unauthenticated response must not contain payload");
  }

  @Test
  void wrongAuthenticationTargetIsRejected() throws Exception {
    UUID payloadId = buffer.put(THREAD, EVENT_JSON).orElseThrow();
    String path = ClusterInternalPaths.realtimePayloadPath(payloadId);

    mockMvc
        .perform(get(path).headers(headers(authentication.sign("GET", path, OTHER_NODE))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void unknownPayloadIsNotFoundAfterAuthentication() throws Exception {
    UUID payloadId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    String path = ClusterInternalPaths.realtimePayloadPath(payloadId);

    mockMvc
        .perform(get(path).headers(headers(authentication.sign("GET", path, NODE))))
        .andExpect(status().isNotFound());
  }

  @Test
  void nonCanonicalUuidPathIsRejected() throws Exception {
    String path =
        ClusterInternalPaths.INTERNAL_REALTIME_PREFIX + "/ABCDEFAB-CDEF-ABCD-EFAB-CDEFABCDEFAB";

    mockMvc
        .perform(get(path).headers(headers(authentication.sign("GET", path, NODE))))
        .andExpect(status().isNotFound());
  }

  private static HttpHeaders headers(Map<String, String> values) {
    HttpHeaders headers = new HttpHeaders();
    values.forEach(headers::add);
    return headers;
  }
}
