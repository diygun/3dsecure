FROM eclipse-temurin:23-jre
LABEL authors="diy"

WORKDIR /app

# Copy the pre-built JAR file into the container
COPY ./out /app/out
COPY ./certificate /app/certificate
COPY ./keystore /app/keystore
COPY ./truststore /app/truststore


# Copy the startup script into the container
COPY ./start-servers.sh /app/start-servers.sh
CMD ls
RUN chmod +x /app/start-servers.sh

# Expose necessary ports (adjust based on your server configurations)
EXPOSE 8443 5050 7070 7071

# Set the startup script as the container entrypoint
ENTRYPOINT ["/app/start-servers.sh"]