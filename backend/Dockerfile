FROM maven:3.9.11-eclipse-temurin-17 AS build

WORKDIR /workspace
ENV MAVEN_OPTS="-Xmx512m -XX:MaxMetaspaceSize=256m"
COPY settings-docker.xml /root/.m2/settings.xml
COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app
COPY --from=build /workspace/target/nexusmind-0.0.1-SNAPSHOT.jar /app/nexusmind.jar

EXPOSE 18081
ENTRYPOINT ["java", "-jar", "/app/nexusmind.jar"]
