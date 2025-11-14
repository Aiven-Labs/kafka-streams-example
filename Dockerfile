FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

COPY run.sh ./
COPY WordCountApp-uber.jar ./

CMD [ "bash", "./run.sh" ]
