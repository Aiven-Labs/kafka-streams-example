# --- First stage: Get our app, work out its dependencies, create a JRE
#FROM eclipse-temurin:21-jdk-alpine AS builder
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /app

ENV FAT_JAR_NAME=FilterApp-uber.jar

# Start with our ("released") fat JAR and our run script
COPY $FAT_JAR_NAME ./
COPY run.sh ./

# Unpack the contents of our fat JAR
RUN mkdir temp && cd temp && jar xf ../$FAT_JAR_NAME
# And find out what depedencies it lacks - this tells us what we need from
# the external Java environment
RUN jdeps --print-module-deps \
    --ignore-missing-deps \
    --recursive \
    --multi-release 17 \
    --class-path="./temp/BOOT-INF/lib/*" \
    --module-path="./temp/BOOT-INF/lib/*" \
    ./$FAT_JAR_NAME > modules.txt

# Now assemble our own custom JRE with only those things in it
RUN $JAVA_HOME/bin/jlink \
    --verbose \
    --add-modules $(cat modules.txt) \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress=zip-6 \
    --output ./custom-jre

# ----------------------------------------------------------------------------
# --- Second stage: Run the actual image
# Use the smallest base image possible (alpine)
##FROM alpine:latest
FROM debian:bookworm-slim

WORKDIR /app

# Install openssl (for run.sh) and RocksDB library (for Kafka Streams)
##RUN apk update && \
##    apk add --no-cache openssl rocksdb && \
##    rm -rf /var/cache/apk/*
RUN apt-get update \
    && apt-get install -y openssl \
    && apt-get install -y librocksdb7.8

RUN apt-get autoremove -y \
    && apt-get clean -y \
    && apt-get autoclean -y \
    && rm -rf /var/lib/apt/lists/*

# Copy the custom JRE and application artifacts from the builder stage
COPY --from=builder /app/custom-jre /usr/lib/jvm/custom-jre
COPY --from=builder /app/FilterApp-uber.jar ./
COPY --from=builder /app/run.sh ./

ENV JAVA_HOME="/usr/lib/jvm/custom-jre"
ENV PATH="$JAVA_HOME/bin:$PATH"

# Copy the entrypoint script and make it executable
COPY run.sh ./
RUN chmod +x ./run.sh

# Set the custom entrypoint
##ENTRYPOINT ["./run.sh"]
CMD [ "./run.sh" ]
