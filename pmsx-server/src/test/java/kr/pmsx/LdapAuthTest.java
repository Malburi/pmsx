package kr.pmsx;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** FR-CM-001 LDAP/AD 인증: AUTH_TYPE=LDAP 으로 등록된 사용자만 LDAP 비밀번호로 로그인 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LdapAuthTest {

    static final InMemoryDirectoryServer LDAP;

    static {
        try {
            InMemoryDirectoryServerConfig cfg = new InMemoryDirectoryServerConfig("dc=pmsx,dc=kr");
            cfg.addAdditionalBindCredentials("cn=admin,dc=pmsx,dc=kr", "ldap-secret");
            cfg.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("default", 0));
            cfg.setSchema(null);
            LDAP = new InMemoryDirectoryServer(cfg);
            LDAP.add("dn: dc=pmsx,dc=kr", "objectClass: top", "objectClass: domain", "dc: pmsx");
            LDAP.add("dn: ou=people,dc=pmsx,dc=kr", "objectClass: organizationalUnit", "ou: people");
            LDAP.add("dn: uid=ldapkim,ou=people,dc=pmsx,dc=kr", "objectClass: inetOrgPerson", "uid: ldapkim",
                    "cn: 김엘디", "sn: 김", "userPassword: Ldap-Pass-1");
            LDAP.add("dn: uid=ldaplee,ou=people,dc=pmsx,dc=kr", "objectClass: inetOrgPerson", "uid: ldaplee",
                    "cn: 이엘디", "sn: 이", "userPassword: Ldap-Pass-2");
            LDAP.startListening();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        LDAP.shutDown(true);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:h2:mem:pmsx-ldap;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        r.add("pmsx.auth.ldap.enabled", () -> "true");
        r.add("pmsx.auth.ldap.url", () -> "ldap://localhost:" + LDAP.getListenPort());
        r.add("pmsx.auth.ldap.base-dn", () -> "dc=pmsx,dc=kr");
        r.add("pmsx.auth.ldap.user-search-filter", () -> "(uid={0})");
        r.add("pmsx.auth.ldap.bind-dn", () -> "cn=admin,dc=pmsx,dc=kr");
        r.add("pmsx.auth.ldap.bind-password", () -> "ldap-secret");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper om;

    private ResultActions login(String id, String pw) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + id + "\",\"password\":\"" + pw + "\"}"));
    }

    @Test
    void LDAP_등록사용자만_LDAP_비밀번호로_로그인() throws Exception {
        String admin = TestSupport.login(mvc, om, "admin");
        // LDAP 계정 등록 (비밀번호 불필요)
        mvc.perform(post("/api/v1/admin/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"ldapkim\",\"name\":\"김엘디\",\"orgType\":\"CUSTOMER\","
                                + "\"authType\":\"LDAP\",\"roles\":[]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.authType").value("LDAP"));

        login("ldapkim", "Ldap-Pass-1").andExpect(status().isOk()).andExpect(jsonPath("$.user.name").value("김엘디"));
        login("ldapkim", "wrong").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
        login("ldapkim", "*").andExpect(status().isUnauthorized());
        // 디렉터리에는 있지만 PMS-X 에 등록되지 않은 사용자는 불가 (자동 가입 없음)
        login("ldaplee", "Ldap-Pass-2").andExpect(status().isUnauthorized());
        // 로컬 계정은 그대로 로컬 비밀번호
        login("analyst", "pmsx1234!").andExpect(status().isOk());
    }
}
