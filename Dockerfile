FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# We need openssl in the `run.sh` script, and Kafka Streams needs rocksdb
# We delete the apk cache when done to save a little bit of space
RUN apk update && \
    apk add --no-cache openssl rocksdb  \
    rm -rf /var/cache/apk/*

COPY run.sh ./
COPY WordCountApp-uber.jar ./

RUN chmod +x ./run.sh

CMD [ "./run.sh" ]
