# ⚠ 미검증: 빌드 환경에 Docker 가 없어 실제 빌드·실행 확인을 하지 않았다.
# 인터넷 되는 곳에서 이미지를 만든 뒤 docker save 로 tar 를 만들어 반입한다.
#   (설치 폴더에서)  docker build -f docker/Dockerfile -t pmsx-server:<버전> .
#                     docker save pmsx-server:<버전> postgres:16-alpine -o pmsx-images.tar
#   (고객망)         docker load -i pmsx-images.tar && docker compose -f docker/docker-compose.yml up -d
FROM eclipse-temurin:21-jre
ENV PMSX_HOME=/opt/pmsx TZ=Asia/Seoul
WORKDIR /opt/pmsx
COPY pmsx-server.jar /opt/pmsx/pmsx-server.jar
COPY config /opt/pmsx/config
RUN mkdir -p /opt/pmsx/data /opt/pmsx/logs /opt/pmsx/run && useradd -r -u 1001 pmsx && chown -R pmsx /opt/pmsx
USER pmsx
EXPOSE 8080
VOLUME ["/opt/pmsx/data", "/opt/pmsx/logs"]
ENTRYPOINT ["java", "-Xms512m", "-Xmx2g", "-Dfile.encoding=UTF-8", "-Dloader.path=/opt/pmsx/lib", "-jar", "/opt/pmsx/pmsx-server.jar", \
            "--spring.profiles.active=prod", "--spring.config.additional-location=optional:file:/opt/pmsx/config/"]
