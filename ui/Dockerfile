# syntax=docker/dockerfile:1.7
#
# lc-checker-ui — built React/Vite SPA served by nginx, with /api/* reverse-
# proxied to the Java service (lc-checker-svc) running on the Mac dev box.
#
# Build: docker build -t lc-checker-ui:latest ui/
# Run:   docker run --rm -p 8083:80 lc-checker-ui:latest
# Composed: see shared-infra/docker-compose.yml service `lc-ui`

############################################################
# Stage 1 — build
############################################################
FROM node:20-alpine AS build

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

############################################################
# Stage 2 — runtime (nginx serving static + reverse-proxy /api)
############################################################
FROM nginx:alpine

COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=3s --retries=3 \
  CMD wget -q --spider http://127.0.0.1/ || exit 1
