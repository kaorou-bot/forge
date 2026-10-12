FROM eclipse-temurin:21-jre
WORKDIR /app
COPY forge-lobby-relay.jar /app/forge-lobby-relay.jar
USER 10001:10001
ENTRYPOINT ["java","-Xms128m","-Xmx512m","-Dforge.relay.bind=0.0.0.0","-Dforge.relay.port=36744","-Dforge.relay.health.bind=0.0.0.0","-Dforge.relay.health.port=36745","-jar","/app/forge-lobby-relay.jar"]
