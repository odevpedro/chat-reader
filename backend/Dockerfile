# --- Build ------------------------------------------------------------------
# Imagem de build: compila o backend com o JDK 21. O cache de dependencias
# reaproveita a camada enquanto o pom.xml nao mudar.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

# --- Runtime ----------------------------------------------------------------
# Imagem final enxuta: apenas o JRE e o jar. Nunca roda como root.
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S app && adduser -S app -G app
WORKDIR /app

COPY --from=build /build/target/chat-reader.jar app.jar

USER app
EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=5 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
