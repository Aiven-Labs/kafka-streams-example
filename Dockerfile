FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

COPY app/build/libs/app-uber.jar ./App.jar
ENTRYPOINT ["java", "-jar", "App.jar"]
## CMD ["java", "-jar", "App.jar"]
