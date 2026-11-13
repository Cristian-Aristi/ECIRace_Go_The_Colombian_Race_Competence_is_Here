# ===================================================
# Etapa 1: Compilación del Servidor Central con Maven
# ===================================================
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /app

# Copiar configuración y dependencias
COPY pom.xml .
COPY src ./src

# Compilar y empaquetar la aplicación Spring Boot
RUN mvn clean package -DskipTests

# ===================================================
# Etapa 2: Imagen Ligera para el Docker Host
# ===================================================
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Usuario sin privilegios: si alguien llegara a ejecutar código en el servidor, no sería root
RUN addgroup -S ecirace && adduser -S -G ecirace -H -s /sbin/nologin ecirace

# Copiar el artefacto JAR generado (solo lectura para el usuario de la aplicación)
COPY --from=builder --chown=root:root /app/target/ecirace-go-0.0.1-SNAPSHOT.jar app.jar
USER ecirace

# Puerto de WebSockets y HTTP (8081: Actuator en el perfil cloud, no se publica)
EXPOSE 8080

# Comando de arranque autoritativo del servidor central
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]