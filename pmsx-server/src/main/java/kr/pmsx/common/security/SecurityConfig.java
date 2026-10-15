package kr.pmsx.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import kr.pmsx.common.ErrorResponse;
import kr.pmsx.common.PmsxProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtService jwtService, ObjectMapper om)
            throws Exception {
        http
                // Bearer 토큰 방식이라 CSRF 토큰은 쓰지 않는다. refresh 쿠키는 SameSite=Lax + Path 제한.
                .csrf(c -> c.disable())
                .cors(c -> {
                })
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        .requestMatchers("/api/v1/system/license-status").permitAll()
                        // 메일 게이트웨이 접수: JWT 대신 X-PMSX-KEY 헤더(설정값)로 확인 (docs/13 §1)
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/sm/inbound-mail").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        // 웹 정적 리소스(설치 패키지의 단일 jar) — SPA 화면 접근 제어는 API 인증으로 이뤄진다
                        .anyRequest().permitAll())
                .headers(h -> h
                        .frameOptions(f -> f.sameOrigin())
                        .contentTypeOptions(c -> {
                        }))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> write(om, res, HttpServletResponse.SC_UNAUTHORIZED,
                                ErrorResponse.of("AUTH_EXPIRED", "다시 로그인해주세요.")))
                        .accessDeniedHandler((req, res, ex) -> write(om, res, HttpServletResponse.SC_FORBIDDEN,
                                ErrorResponse.of("FORBIDDEN", "권한이 없습니다."))))
                .addFilterBefore(new JwtAuthFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void write(ObjectMapper om, HttpServletResponse res, int status, ErrorResponse body)
            throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        om.writeValue(res.getOutputStream(), body);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(PmsxProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(props.cors() == null || props.cors().allowedOrigins() == null
                ? List.of() : props.cors().allowedOrigins());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
