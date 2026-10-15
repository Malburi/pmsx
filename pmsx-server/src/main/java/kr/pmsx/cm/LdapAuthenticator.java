package kr.pmsx.cm;

import kr.pmsx.common.PmsxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.ldap.support.LdapEncoder;
import org.springframework.stereotype.Component;

/**
 * LDAP/AD 인증 (FR-CM-001, docs/10 §4). pmsx.auth.ldap.enabled=true 일 때만 동작.
 * AUTH_TYPE=LDAP 으로 미리 등록된 사용자만 이 경로로 인증한다(자동 가입 없음).
 */
@Component
public class LdapAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(LdapAuthenticator.class);

    private final PmsxProperties.Ldap cfg;
    private volatile LdapTemplate template;

    public LdapAuthenticator(PmsxProperties props) {
        this.cfg = props.ldap();
    }

    public boolean enabled() {
        return cfg.enabled() && cfg.url() != null && !cfg.url().isBlank();
    }

    public boolean authenticate(String loginId, String password) {
        if (!enabled() || password == null || password.isEmpty()) {
            return false;
        }
        String filterTemplate = cfg.userSearchFilter() == null || cfg.userSearchFilter().isBlank()
                ? "(uid={0})" : cfg.userSearchFilter();
        String filter = filterTemplate.replace("{0}", LdapEncoder.filterEncode(loginId));
        try {
            return template().authenticate("", filter, password);
        } catch (RuntimeException e) {
            log.warn("LDAP 인증 오류: {}", e.getMessage());
            return false;
        }
    }

    private LdapTemplate template() {
        LdapTemplate t = template;
        if (t == null) {
            synchronized (this) {
                if (template == null) {
                    LdapContextSource cs = new LdapContextSource();
                    cs.setUrl(cfg.url());
                    cs.setBase(cfg.baseDn() == null ? "" : cfg.baseDn());
                    if (cfg.bindDn() != null && !cfg.bindDn().isBlank()) {
                        cs.setUserDn(cfg.bindDn());
                        cs.setPassword(cfg.bindPassword() == null ? "" : cfg.bindPassword());
                    } else {
                        cs.setAnonymousReadOnly(true);
                    }
                    cs.afterPropertiesSet();
                    LdapTemplate lt = new LdapTemplate(cs);
                    lt.setIgnorePartialResultException(true);
                    template = lt;
                }
                t = template;
            }
        }
        return t;
    }
}
