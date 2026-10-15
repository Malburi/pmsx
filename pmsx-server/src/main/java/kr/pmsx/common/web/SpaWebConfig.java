package kr.pmsx.common.web;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * 설치 패키지(단일 jar)에서 웹 빌드 결과(classpath:/static)를 서빙한다.
 * 파일이 없는 경로는 SPA 라우팅을 위해 index.html 로 돌려준다. /api/** 는 제외(404 JSON 유지).
 * 개발 환경(static/index.html 없음)에서는 아무 것도 서빙하지 않는다.
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        if (resourcePath.startsWith("api/") || resourcePath.startsWith("v3/")
                                || resourcePath.startsWith("swagger-ui")) {
                            return null;
                        }
                        // 확장자가 있는 정적 파일 요청(예: /assets/x.js)은 index.html 로 바꾸지 않는다
                        String last = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
                        if (last.contains(".")) {
                            return null;
                        }
                        Resource index = new ClassPathResource("static/index.html");
                        return index.exists() ? index : null;
                    }
                });
    }
}
