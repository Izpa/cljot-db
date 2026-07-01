# Multi-stage build of the cljot-crud bot uberjar.
# Build context is the REPO ROOT (see docker-compose.yml: build.context = ..).
# Only src/resources/build files are copied, so deploy/data (videos) and target/
# never enter the build context.

FROM clojure:temurin-21-tools-deps AS build
WORKDIR /build
# Prefetch deps first for better layer caching.
COPY deps.edn build.clj ./
RUN clojure -P -T:build noop 2>/dev/null || true
# Sources
COPY src ./src
COPY resources ./resources
RUN clojure -T:build uber

# Slim runtime
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/cljot.jar /app/cljot.jar
# Long-polling mode when SELF_URL is unset -> no inbound port required.
CMD ["java", "-jar", "/app/cljot.jar"]
