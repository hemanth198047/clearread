# ClearRead SPA — Vite build + nginx
FROM node:22-alpine AS build
WORKDIR /app

COPY package.json package-lock.json* ./
RUN npm ci || npm install

COPY . .
# Normalize scripts if checked out on Windows
RUN find . -name "*.sh" -exec sed -i 's/\r$//' {} + 2>/dev/null || true

ARG VITE_API_BASE_URL=http://localhost:8080
ARG VITE_OIDC_AUTHORITY=http://localhost:8081/realms/clearread
ARG VITE_OIDC_CLIENT_ID=clearread-spa
ARG CLEARREAD_API_ORIGIN=http://localhost:8080
ARG CLEARREAD_OIDC_ORIGIN=http://localhost:8081
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL \
    VITE_OIDC_AUTHORITY=$VITE_OIDC_AUTHORITY \
    VITE_OIDC_CLIENT_ID=$VITE_OIDC_CLIENT_ID
RUN npm run build

FROM nginx:1.27-alpine
ARG CLEARREAD_API_ORIGIN=http://localhost:8080
ARG CLEARREAD_OIDC_ORIGIN=http://localhost:8081
COPY nginx.conf /etc/nginx/conf.d/default.conf
RUN sed -i \
  -e "s|__CLEARREAD_API_ORIGIN__|${CLEARREAD_API_ORIGIN}|g" \
  -e "s|__CLEARREAD_OIDC_ORIGIN__|${CLEARREAD_OIDC_ORIGIN}|g" \
  /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=5s --timeout=3s --start-period=5s --retries=6 \
  CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
