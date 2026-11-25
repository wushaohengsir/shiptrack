# syntax=docker/dockerfile:1

# ---- build stage -----------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /build

# Dependencies first so a source-only change reuses this layer. The lockfile is
# the source of truth: `npm ci`, never `npm install`.
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .

# Vite inlines import.meta.env at build time. The compose service calls the API
# through nginx on the same origin, so the base URL stays relative.
ARG VITE_API_MODE=http
ARG VITE_API_BASE_URL=/api/v1
ENV VITE_API_MODE=${VITE_API_MODE} \
    VITE_API_BASE_URL=${VITE_API_BASE_URL}
RUN npm run build

# ---- runtime stage ---------------------------------------------------------
# nginx-unprivileged runs as uid 101, listens on 8080 and keeps its pid and
# temp files under /tmp, so the container works with a read-only root
# filesystem and cap_drop ALL.
FROM nginxinc/nginx-unprivileged:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build --chown=101:101 /build/dist /usr/share/nginx/html

USER 101:101
EXPOSE 8080
