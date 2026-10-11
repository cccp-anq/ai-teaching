package com.aijiaoxue;

import com.aijiaoxue.common.web.RequestIdFilter;
import com.aijiaoxue.course.access.AuthorizedOfferingRepository;
import com.aijiaoxue.course.access.OfferingReference;
import com.aijiaoxue.fixtures.R__Phase1_acceptance_fixture;
import com.aijiaoxue.identity.*;
import com.aijiaoxue.identity.authentication.AccountPrincipal;
import com.aijiaoxue.identity.authentication.DatabaseUserDetailsService;
import com.aijiaoxue.identity.web.SecurityErrorWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.util.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.JavaMigration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({AuthenticationIntegrationTest.FixtureConfiguration.class, AuthenticationIntegrationTest.ProbeController.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional
class AuthenticationIntegrationTest {
    private static final String TEST_PASSWORD = "Fixture-Only-2026!";
    private static final String PROBE = "/api/v1/_authz-test";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired WebApplicationContext context;
    @Autowired RequestIdFilter requestIds;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired DatabaseUserDetailsService userDetails;
    @Autowired CurrentUserProvider users;
    @Autowired AuthorizedScopeProvider scopes;
    @Autowired AuthorizedOfferingRepository offerings;
    @Autowired PermissionService permissions;
    @Value("${local.server.port}") int port;
    MockMvc mvc;

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) throws Exception {
        boolean mysql = Boolean.getBoolean("mysqlMigration");
        String url = mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_URL"))
                : "jdbc:h2:mem:phase1-fixture-auth;MODE=MySQL;DB_CLOSE_DELAY=-1";
        if (mysql && !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/ai_teaching_migration_[a-f0-9]{32}\\?sslMode=DISABLED&allowPublicKeyRetrieval=true"))
            throw new IllegalArgumentException("Dedicated MySQL test database required");
        String user = mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_USERNAME")) : "sa";
        String password = mysql ? Objects.requireNonNull(System.getenv("MIGRATION_TEST_PASSWORD")) : "";
        try (var db = DriverManager.getConnection(url,user,password);
             var tables = db.getMetaData().getTables(db.getCatalog(),mysql ? null : "PUBLIC","%",new String[]{"TABLE"})) {
            assertFalse(tables.next(), "Authorization suite refuses nonempty databases");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> user);
        properties.add("spring.datasource.password", () -> password);
        properties.add("spring.datasource.driver-class-name", () -> mysql ? "com.mysql.cj.jdbc.Driver" : "org.h2.Driver");
        properties.add("server.servlet.session.cookie.secure", () -> true);
    }

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIds).apply(springSecurity()).build();
    }
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }
    @AfterAll void cleanup() throws Exception {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .javaMigrations(new R__Phase1_acceptance_fixture(false,true)).cleanDisabled(true).load().migrate();
        for (String table : R__Phase1_acceptance_fixture.TABLES)
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
    }

    @Test void auth01LoginCreatesTrustedCurrentUserAndErasesCredentials() throws Exception {
        mvc.perform(get("/api/v1/auth/login-mode")).andExpect(status().isOk())
                .andExpect(jsonPath("$.usernameOnly").value(false));
        var session = login("teacher-a-test");
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(3)).andExpect(jsonPath("$.teacherId").value(1))
                .andExpect(jsonPath("$.roles").isArray()).andExpect(jsonPath("$.authorizationScopes.length()").value(2))
                .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.passwordHash").doesNotExist());
        var security = (org.springframework.security.core.context.SecurityContext)session.getAttribute("SPRING_SECURITY_CONTEXT");
        assertNull(security.getAuthentication().getCredentials());
        assertNull(((AccountPrincipal)security.getAuthentication().getPrincipal()).getPassword());
    }

    @ParameterizedTest @ValueSource(strings={"teacher-a-test","missing-test"})
    void auth02WrongCredentialsNeverAuthenticate(String username) throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(session).with(csrf())
                .param("username",username).param("password","wrong"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        assertNull(session.getAttribute("SPRING_SECURITY_CONTEXT"));
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test void auth03DisabledAccountCannotLoginOrUseExistingSession() throws Exception {
        var session = login("teacher-b-test");
        jdbc.update("UPDATE user_account SET enabled=FALSE WHERE id=4");
        assertFalse(userDetails.loadUserByUsername("teacher-b-test").isEnabled());
        mvc.perform(post("/api/v1/auth/login").with(csrf()).param("username","teacher-b-test").param("password",TEST_PASSWORD))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("DISABLED_ACCOUNT"));
        mvc.perform(get(PROBE+"/offerings").session(session)).andExpect(status().isUnauthorized());
    }

    @Test void authz01DatabaseListIsRestrictedBeforeUserFilters() throws Exception {
        var session = login("teacher-a-test");
        assertEquals(List.of(101L,102L,103L,104L), list(session,""));
        assertEquals(List.of(), list(session,"?collegeId=2"));
        runAs(3);
        assertEquals(List.of(101L,102L,103L,104L),offerings.list(null,null).stream().map(OfferingReference::id).toList());
    }

    @Test void authz02ManualObjectUrlCannotReadInvisibleOrMissingOffering() throws Exception {
        var session = login("teacher-a-test");
        mvc.perform(get(PROBE+"/offerings/101").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(101));
        for (long id : List.of(109L,99999L)) {
            mvc.perform(get(PROBE+"/offerings/"+id).session(session).header("X-Request-ID","object-denied"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_VISIBLE"))
                    .andExpect(jsonPath("$.requestId").value("object-denied"))
                    .andExpect(jsonPath("$.displayName").doesNotExist());
        }
    }

    @Test void authz03AdminHasImportCapabilityAndGlobalRead() throws Exception {
        var session = login("admin-test");
        assertEquals(10,list(session,"").size());
        mvc.perform(post(PROBE+"/import-check").session(session).with(csrf())).andExpect(status().isNoContent());
    }

    @ParameterizedTest @ValueSource(strings={"director-a-test","teacher-a-test","teacher-b-test","supervisor-a-test"})
    void authz04NonAdminCannotImportEvenWithForgedRole(String username) throws Exception {
        mvc.perform(post(PROBE+"/import-check").session(login(username)).with(csrf()).param("role","ADMIN").header("X-Role","ADMIN"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test void authz05SupervisorOnlySeesExplicitOfferingTargets() throws Exception {
        var session = login("supervisor-a-test");
        assertEquals(List.of(104L),list(session,""));
        mvc.perform(get(PROBE+"/offerings/101").session(session)).andExpect(status().isNotFound());
        assertEquals(10,list(login("director-a-test"),"").size());
    }

    @Test void authz06RepeatedUsersNeverShareMutableScopes() throws Exception {
        var alpha = login("teacher-a-test");
        var beta = login("teacher-b-test");
        assertEquals(List.of(101L,102L,103L,104L),list(alpha,""));
        assertEquals(List.of(102L,104L,110L),list(beta,""));
        assertEquals(List.of(101L,102L,103L,104L),list(alpha,""));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test void authz07DifferentScheduleTeachersBothReadOneOffering() throws Exception {
        // Rebind the existing second test account explicitly, never by matching a name.
        jdbc.update("UPDATE user_account SET teacher_id=3 WHERE id=4");
        jdbc.update("UPDATE user_role_scope SET teacher_id=3 WHERE id=4");
        var alpha=login("teacher-a-test");
        var gamma=login("teacher-b-test");
        mvc.perform(get(PROBE+"/offerings/103").session(alpha)).andExpect(status().isOk());
        mvc.perform(get(PROBE+"/offerings/103").session(gamma)).andExpect(status().isOk());
        assertEquals(List.of(103L,107L),list(gamma,""));
    }

    @Test void authz08ClientScopeAndTeacherCanOnlyNarrowDataset() throws Exception {
        var session=login("teacher-a-test");
        assertEquals(List.of(),list(session,"?collegeId=2&teacherId=5&role=ADMIN&scope=GLOBAL"));
        assertEquals(List.of(101L,102L,103L,104L),list(session,"?role=ADMIN&scope=GLOBAL&userId=1"));
        assertEquals(List.of(102L,104L),list(session,"?teacherId=2"));
        mvc.perform(get(PROBE+"/offerings/109").session(session).param("role","ADMIN").header("X-User-Id","1"))
                .andExpect(status().isNotFound());
    }

    @Test void rolesScopesAndBindingsAreReloadedAfterLogin() throws Exception {
        var session=login("teacher-a-test");
        jdbc.update("DELETE FROM user_role_scope WHERE user_id=3");
        assertEquals(List.of(),list(session,""));
        mvc.perform(get(PROBE+"/offerings/101").session(session)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(jsonPath("$.roles").isEmpty());
    }

    @Test void nameOnlyDoesNotAuthenticateOrBindByMatchingUsername() throws Exception {
        jdbc.update("UPDATE user_account SET username='Teacher Alpha',teacher_id=NULL WHERE id=3");
        jdbc.update("DELETE FROM user_role_scope WHERE id=6");
        assertEquals(List.of(),list(login("Teacher Alpha"),""));
    }

    @Test void mismatchedSelfTeacherAndUnapprovedCollegeOrRoleFailClosed() throws Exception {
        jdbc.update("UPDATE user_role_scope SET teacher_id=5 WHERE id=3");
        jdbc.update("DELETE FROM user_role_scope WHERE id=6");
        var session=login("teacher-a-test");
        assertEquals(List.of(),list(session,""));
        jdbc.update("UPDATE user_role_scope SET scope_type='COLLEGE',teacher_id=NULL,college_id=1 WHERE id=3");
        assertEquals(List.of(),list(session,""));
        jdbc.update("UPDATE role SET role_code='UNAPPROVED' WHERE id=3");
        assertEquals(List.of(),list(session,""));
    }

    @Test void nonconflictingUnionKeepsTeachingAndSupervisionPurposesDistinct() {
        runAs(3);
        var user=users.requireCurrentUser();
        assertEquals(Set.of(1L),scopes.resolve(user,AuthorizedScopeProvider.Purpose.TEACHING).teacherIds());
        assertTrue(scopes.resolve(user,AuthorizedScopeProvider.Purpose.TEACHING).offeringIds().isEmpty());
        assertEquals(Set.of(104L),scopes.resolve(user,AuthorizedScopeProvider.Purpose.SUPERVISION).offeringIds());
        assertTrue(scopes.resolve(user,AuthorizedScopeProvider.Purpose.SUPERVISION).teacherIds().isEmpty());
        assertThrows(UnsupportedOperationException.class,()->user.roles().clear());
        assertThrows(UnsupportedOperationException.class,()->user.authorizationScopes().clear());
        runAs(1);
        assertFalse(scopes.resolve(users.requireCurrentUser(),AuthorizedScopeProvider.Purpose.TEACHING).global());
        assertFalse(scopes.resolve(users.requireCurrentUser(),AuthorizedScopeProvider.Purpose.SUPERVISION).global());
    }

    @Test void sourceCollegeNeverBecomesAutomaticOwnershipOrPermission() throws Exception {
        jdbc.update("UPDATE course_offering SET source_scope_college_id=2 WHERE id=101");
        jdbc.update("UPDATE course SET owner_college_id=2 WHERE id=201");
        var session=login("teacher-a-test");
        assertEquals(List.of(101L),list(session,"?collegeId=2"));
        mvc.perform(get(PROBE+"/offerings/109").session(session)).andExpect(status().isNotFound());
    }

    @Test void sessionFixationCsrfRotationAndLogoutInvalidation() throws Exception {
        var anonymous=mvc.perform(get("/api/v1/auth/csrf")).andReturn();
        var oldToken=JSON.readTree(anonymous.getResponse().getContentAsString()).get("token").asText();
        var session=(MockHttpSession)anonymous.getRequest().getSession(false);
        String oldId=session.getId();
        mvc.perform(post("/api/v1/auth/login").session(session).header("X-CSRF-TOKEN",oldToken)
                .param("username","teacher-a-test").param("password",TEST_PASSWORD)).andExpect(status().isOk());
        assertNotEquals(oldId,session.getId());
        mvc.perform(post("/api/v1/auth/logout").session(session).header("X-CSRF-TOKEN",oldToken)).andExpect(status().isForbidden());
        var tokenResponse=mvc.perform(get("/api/v1/auth/csrf").session(session)).andReturn();
        String token=JSON.readTree(tokenResponse.getResponse().getContentAsString()).get("token").asText();
        mvc.perform(post("/api/v1/auth/logout").session(session).header("X-CSRF-TOKEN",token)).andExpect(status().isNoContent());
        assertTrue(session.isInvalid());
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test void csrfIsRequiredForLoginImportAndLogout() throws Exception {
        mvc.perform(post("/api/v1/auth/login").param("username","admin-test").param("password",TEST_PASSWORD))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        var session=login("admin-test");
        mvc.perform(post(PROBE+"/import-check").session(session)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/v1/auth/logout").session(session)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk());
    }

    @Test void unauthenticatedAndForgedPrincipalsCannotUseProtectedObjects() throws Exception {
        mvc.perform(get(PROBE+"/offerings")).andExpect(status().isUnauthorized());
        mvc.perform(get(PROBE+"/offerings/101")).andExpect(status().isUnauthorized());
        mvc.perform(get(PROBE+"/offerings").with(user("admin-test").roles("ADMIN")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/courses").session(login("admin-test"))).andExpect(status().isForbidden());
    }

    @Test void realServletCookieIsHttpOnlySecureAndSameSiteLax() throws Exception {
        var client=HttpClient.newHttpClient();
            var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());
            String cookie=response.headers().firstValue("set-cookie").orElseThrow().toLowerCase(Locale.ROOT);
            assertTrue(cookie.contains("httponly")); assertTrue(cookie.contains("secure")); assertTrue(cookie.contains("samesite=lax"));
            assertFalse(response.body().contains("password"));
    }

    @Test void fixturePasswordsAreHashesAndNeverCopiedIntoCurrentUser() {
        String hash=jdbc.queryForObject("SELECT password_hash FROM user_account WHERE id=3",String.class);
        assertTrue(hash.startsWith("$2a$12$")); assertNotEquals(TEST_PASSWORD,hash);
        assertTrue(passwords.matches(TEST_PASSWORD,hash));
        runAs(3);
        assertFalse(JSON.writeValueAsString(users.requireCurrentUser()).contains(hash));
    }

    private MockHttpSession login(String username) throws Exception {
        return (MockHttpSession)mvc.perform(post("/api/v1/auth/login").with(csrf()).param("username",username).param("password",TEST_PASSWORD))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
    private List<Long> list(MockHttpSession session,String query) throws Exception {
        var response=mvc.perform(get(PROBE+"/offerings"+query).session(session)).andExpect(status().isOk()).andReturn().getResponse();
        List<Long> ids=new ArrayList<>();
        for(var row:JSON.readTree(response.getContentAsString())) ids.add(row.get("id").asLong());
        return ids;
    }
    private void runAs(long id) {
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new AccountPrincipal(id,"test-principal","",true),null,List.of()));
        SecurityContextHolder.setContext(context);
    }

    @TestConfiguration(proxyBeanMethods=false)
    static class FixtureConfiguration {
        @Bean JavaMigration authFixture() throws Exception { return new R__Phase1_acceptance_fixture(true,true); }
        @Bean @Order(0) SecurityFilterChain testRoutes(HttpSecurity http,SecurityErrorWriter errors) throws Exception {
            return http.securityMatcher(PROBE+"/**").authorizeHttpRequests(r->r.anyRequest().authenticated())
                    .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->errors.write(req,res,401,"UNAUTHENTICATED"))
                            .accessDeniedHandler((req,res,ex)->errors.write(req,res,403,"FORBIDDEN"))).build();
        }
    }
    @RestController
    static class ProbeController {
        private final AuthorizedOfferingRepository offerings;
        private final PermissionService permissions;
        ProbeController(AuthorizedOfferingRepository offerings,PermissionService permissions) { this.offerings=offerings; this.permissions=permissions; }
        @GetMapping("/_authz-test/offerings") List<OfferingReference> list(@RequestParam(required=false) Long teacherId,@RequestParam(required=false) Long collegeId) {
            return offerings.list(teacherId,collegeId);
        }
        @GetMapping("/_authz-test/offerings/{id}") OfferingReference object(@PathVariable long id) { return offerings.requireVisible(id); }
        @PostMapping("/_authz-test/import-check") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
        void importCheck() { permissions.requireTimetableImport(); }
    }
}
