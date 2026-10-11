package com.aijiaoxue.action;

import com.aijiaoxue.fixtures.R__Phase1_acceptance_fixture;
import org.flywaydb.core.api.migration.JavaMigration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.sql.DriverManager;
import java.util.Objects;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sprint 2 acceptance for the teacher action chain (S2-08 选择一项下次尝试, S2-09 管理我的当前行动,
 * S2-14 调整行动内容). PRIV3-01 is the load-bearing rule: an action is visible and writable only
 * by its owner, and a foreign identifier is answered like a missing one so existence never leaks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TeacherActionIntegrationTest.FixtureConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional
class TeacherActionIntegrationTest {
    private static final String TEST_PASSWORD = "Fixture-Only-2026!";
    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws Exception {
        String url = "jdbc:h2:mem:phase1-fixture-teacher-action;MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (var db = DriverManager.getConnection(url, "sa", "");
             var tables = db.getMetaData().getTables(db.getCatalog(), "PUBLIC", "%", new String[]{"TABLE"})) {
            if (tables.next()) throw new IllegalStateException("Teacher action suite refuses nonempty databases");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> "sa");
        properties.add("spring.datasource.password", () -> "");
        properties.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        properties.add("server.servlet.session.cookie.secure", () -> false);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean JavaMigration teacherActionFixture() throws Exception {
            return new R__Phase1_acceptance_fixture(true, true);
        }
    }

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(com.aijiaoxue.common.web.RequestIdFilter.class))
                .apply(springSecurity()).build();
    }

    private MockHttpSession login(String username) throws Exception {
        var session = mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .param("username", username).param("password", TEST_PASSWORD))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        return (MockHttpSession) Objects.requireNonNull(session);
    }

    private long createAction(MockHttpSession session, String body) throws Exception {
        String created = mvc.perform(post("/api/v1/teacher-actions").contentType(MediaType.APPLICATION_JSON)
                        .content(body).with(csrf()).session(session))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonMapper.builder().build().readTree(created).get("actionId").asLong();
    }

    @Test
    void teacherCreatesAdjustsAndFinishesOwnAction() throws Exception {
        var teacher = login("teacher-a-test");
        long actionId = createAction(teacher, """
                {"mediaId":41,"sourceKind":"SUGGESTION","sourceIndex":0,
                 "title":"提问后停顿三秒","content":"下一节课提问后停顿三秒再点名"}""");

        mvc.perform(get("/api/v1/teacher-actions").session(teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].actionId").value(actionId))
                .andExpect(jsonPath("$[0].status").value("PLANNED"))
                .andExpect(jsonPath("$[0].sourceKind").value("SUGGESTION"))
                .andExpect(jsonPath("$[0].sourceIndex").value(0))
                .andExpect(jsonPath("$[0].mediaId").value(41));

        mvc.perform(put("/api/v1/teacher-actions/" + actionId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"提问后停顿三秒\",\"content\":\"提问后停顿三秒，再请学生补充\"}")
                        .with(csrf()).session(teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("提问后停顿三秒，再请学生补充"))
                .andExpect(jsonPath("$.revision").value(2));

        mvc.perform(post("/api/v1/teacher-actions/" + actionId + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}")
                        .with(csrf()).session(teacher))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));

        mvc.perform(post("/api/v1/teacher-actions/" + actionId + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DONE\"}")
                        .with(csrf()).session(teacher))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test
    void actionFromAnotherTeacherIsNeitherListedNorReadable() throws Exception {
        var owner = login("teacher-a-test");
        long actionId = createAction(owner, """
                {"sourceKind":"MANUAL","title":"先给示例","content":"下一次先给一个示例再让学生练习"}""");

        var other = login("teacher-b-test");
        mvc.perform(get("/api/v1/teacher-actions").session(other))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        mvc.perform(put("/api/v1/teacher-actions/" + actionId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"篡改\",\"content\":\"别人的行动\"}")
                        .with(csrf()).session(other))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ACTION_NOT_FOUND"));

        mvc.perform(post("/api/v1/teacher-actions/" + actionId + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DONE\"}")
                        .with(csrf()).session(other))
                .andExpect(status().isNotFound());

        // The owner's row is untouched by the rejected attempt.
        mvc.perform(get("/api/v1/teacher-actions").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("先给示例"))
                .andExpect(jsonPath("$[0].status").value("PLANNED"))
                .andExpect(jsonPath("$[0].revision").value(1));
    }

    @Test
    void invalidRequestsAndTransitionsAreRejected() throws Exception {
        var teacher = login("teacher-a-test");
        long actionId = createAction(teacher,
                "{\"sourceKind\":\"MANUAL\",\"title\":\"标题\",\"content\":\"内容\"}");

        mvc.perform(post("/api/v1/teacher-actions/" + actionId + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DONE\"}")
                        .with(csrf()).session(teacher))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ACTION_TRANSITION"));

        mvc.perform(post("/api/v1/teacher-actions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceKind\":\"MANUAL\",\"title\":\"  \",\"content\":\"内容\"}")
                        .with(csrf()).session(teacher))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACTION_FIELD_REQUIRED"));

        mvc.perform(post("/api/v1/teacher-actions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceKind\":\"SUGGESTION\",\"title\":\"标题\",\"content\":\"内容\"}")
                        .with(csrf()).session(teacher))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUGGESTION_INDEX_REQUIRED"));

        mvc.perform(post("/api/v1/teacher-actions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceKind\":\"IMPORTED\",\"title\":\"标题\",\"content\":\"内容\"}")
                        .with(csrf()).session(teacher))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SOURCE_KIND"));
    }

    @Test
    void anonymousCallerCannotUseTheActionChain() throws Exception {
        mvc.perform(get("/api/v1/teacher-actions")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/teacher-actions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceKind\":\"MANUAL\",\"title\":\"标题\",\"content\":\"内容\"}")
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }
}
