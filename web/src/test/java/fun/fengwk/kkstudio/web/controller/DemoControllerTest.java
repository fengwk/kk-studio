package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.web.WebTestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.web.WebTestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * @author fengwk
 */
@AutoConfigureMockMvc
@SpringBootTest(classes = WebTestApplication.class)
public class DemoControllerTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Test
  public void shouldCreatePageAndRemoveDemo() throws Exception {
    MvcResult createResult =
        mockMvc
            .perform(
                post("/api/demo")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                    {
                      "name": "demo-web"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.name").value("demo-web"))
            .andReturn();

    JsonNode data =
        objectMapper.readTree(createResult.getResponse().getContentAsString()).path("data");
    long id = data.path("id").asLong();

    MvcResult pageResult =
        mockMvc
            .perform(get("/api/demo").param("pageNumber", "1").param("pageSize", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.results[0].id").exists())
            .andReturn();

    JsonNode pageData =
        objectMapper.readTree(pageResult.getResponse().getContentAsString()).path("data");
    assertTrue(pageData.path("totalCount").asLong() >= 1);

    mockMvc.perform(delete("/api/demo/{id}", id)).andExpect(status().isNoContent());
  }
}
