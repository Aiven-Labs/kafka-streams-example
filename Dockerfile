FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN apk update && apk add --no-cache openssl

# Install RocksDB library for Kafka Streams state stores.
RUN apk update && apk add --no-cache openssl rocksdb

COPY run.sh ./
COPY WordCountApp-uber.jar ./

RUN chmod +x ./run.sh

CMD [ "./run.sh" ]
