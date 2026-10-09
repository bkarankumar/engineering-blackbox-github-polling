# ---------- React build ----------
FROM node:22-alpine AS frontend-build

WORKDIR /frontend

COPY frontend/package*.json ./
RUN npm install

COPY frontend/ ./
RUN npm run build


# ---------- Spring Boot build ----------
FROM gradle:8.14-jdk17 AS backend-build

WORKDIR /workspace

COPY backend/ ./backend/
COPY --from=frontend-build /frontend/dist ./frontend/dist

WORKDIR /workspace/backend

RUN mkdir -p src/main/resources/static     && cp -r ../frontend/dist/. src/main/resources/static/     && gradle clean bootJar --no-daemon


# ---------- Runtime ----------
FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=backend-build     /workspace/backend/build/libs/*.jar     app.jar

ENV PORT=8080

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
