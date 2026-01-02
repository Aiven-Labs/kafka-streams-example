# --- First stage: Get our app, work out its dependencies, create a JRE
FROM gradle:9.2.1-jdk21-jammy AS builder
# See https://hub.docker.com/_/gradle for available images
# This one is built on top of
#    eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /app

# Define which app we're building
# You can override this at the `docker build` command line
# with `--build-arg APP_NAME=<different name>`
# (see https://docs.docker.com/build/building/variables/#env-usage-example)
ARG APP_NAME="GenericLogApp"

# Copy our gradle build environment over
RUN mkdir app
COPY app ./app/

RUN mkdir gradle
COPY gradle ./gradle/

COPY settings.gradle ./

# And the run scripts we'll need in stage 2
COPY run.sh ./
COPY setup_auth.sh ./

# Start by building the app as a fat (uber) JAR
# This gives us a smaller executable in stage 2
RUN gradle clean ${APP_NAME}UberJar --no-daemon

ENV FAT_JAR_NAME=${APP_NAME}-uber.jar
RUN cp app/build/libs/$FAT_JAR_NAME ./

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
FROM debian:bookworm-slim

WORKDIR /app

# Install openssl (for run.sh) and RocksDB library (for Kafka Streams)
RUN apt-get update \
    && apt-get install -y openssl \
    && apt-get install -y librocksdb7.8

RUN apt-get autoremove -y \
    && apt-get clean -y \
    && apt-get autoclean -y \
    && rm -rf /var/lib/apt/lists/*

# Copy the custom JRE and application artifacts from the builder stage
COPY --from=builder /app/custom-jre /usr/lib/jvm/custom-jre
COPY --from=builder /app/*-uber.jar ./
COPY --from=builder /app/setup_auth.sh ./
COPY --from=builder /app/run.sh ./

ENV JAVA_HOME="/usr/lib/jvm/custom-jre"
ENV PATH="$JAVA_HOME/bin:$PATH"

# ARG values are not persisted into the run time (that is, our `run.sh`)
# So first get the ARG value with the same value as in the first stage
# (if we don't do this, it won't be available, as ARG values don't last
# past the end of a build stage)
ARG APP_NAME
# And then set an ENV value to that value
ENV APP_NAME=$APP_NAME

# Copy the entrypoint script and make it executable
COPY run.sh ./
RUN chmod +x ./run.sh
RUN chmod +x ./setup_auth.sh

# Set the custom entrypoint
CMD [ "./run.sh" ]
