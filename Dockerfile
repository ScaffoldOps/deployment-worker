FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN mvn -B -DskipTests package
FROM registry.k8s.io/kubectl:v1.34.1 AS kubectl
FROM eclipse-temurin:17-jre
COPY --from=kubectl /bin/kubectl /usr/local/bin/kubectl
WORKDIR /app
COPY --from=build /build/target/deployment-worker-0.0.1-SNAPSHOT.jar app.jar
USER 10001
ENTRYPOINT ["java","-jar","app.jar"]
