# ---------- Stage 1: build ----------
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY LmsApp.java .
RUN javac -encoding UTF-8 LmsApp.java

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/*.class ./
COPY assets ./assets
COPY ghouri-logo.png .
ENV PORT=8080
EXPOSE 8080
CMD ["java", "-XX:+UseSerialGC", "-Xmx384m", "-cp", ".", "LmsApp"]
