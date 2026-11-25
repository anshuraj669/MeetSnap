# MeetSnap web app: reproducible build + a small, non-root static server.
#
# The live site is deployed to Firebase Hosting by the CI/CD pipeline (.github/workflows/ci-cd.yml).
# This image is for running the same build anywhere else: locally, on Cloud Run, or on any container host.
#
#   docker build -t meetsnap \
#     --build-arg VITE_FIREBASE_API_KEY=... --build-arg VITE_FIREBASE_AUTH_DOMAIN=... \
#     --build-arg VITE_FIREBASE_PROJECT_ID=... --build-arg VITE_FIREBASE_STORAGE_BUCKET=... \
#     --build-arg VITE_FIREBASE_MESSAGING_SENDER_ID=... --build-arg VITE_FIREBASE_APP_ID=... \
#     --build-arg VITE_RECAPTCHA_ENTERPRISE_SITE_KEY=... .
#   docker run -p 8080:8080 meetsnap        # → http://localhost:8080
#
# The VITE_* values are the public Firebase web config: they end up in the JavaScript bundle by design.
# Never pass App Check debug tokens or service-account keys as build args.

# ---- 1. Build ---------------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /app

# Install dependencies first so this layer is cached between code changes.
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY tsconfig.json index.html ./
COPY public ./public
COPY src ./src

ARG VITE_FIREBASE_API_KEY
ARG VITE_FIREBASE_AUTH_DOMAIN
ARG VITE_FIREBASE_PROJECT_ID
ARG VITE_FIREBASE_STORAGE_BUCKET
ARG VITE_FIREBASE_MESSAGING_SENDER_ID
ARG VITE_FIREBASE_APP_ID
ARG VITE_RECAPTCHA_ENTERPRISE_SITE_KEY
ENV VITE_FIREBASE_API_KEY=$VITE_FIREBASE_API_KEY \
    VITE_FIREBASE_AUTH_DOMAIN=$VITE_FIREBASE_AUTH_DOMAIN \
    VITE_FIREBASE_PROJECT_ID=$VITE_FIREBASE_PROJECT_ID \
    VITE_FIREBASE_STORAGE_BUCKET=$VITE_FIREBASE_STORAGE_BUCKET \
    VITE_FIREBASE_MESSAGING_SENDER_ID=$VITE_FIREBASE_MESSAGING_SENDER_ID \
    VITE_FIREBASE_APP_ID=$VITE_FIREBASE_APP_ID \
    VITE_RECAPTCHA_ENTERPRISE_SITE_KEY=$VITE_RECAPTCHA_ENTERPRISE_SITE_KEY

# Type-check and bundle (same command as CI).
RUN npm run build

# ---- 2. Serve ---------------------------------------------------------------
# nginx-unprivileged runs as a non-root user and listens on 8080 (what Cloud Run expects).
FROM nginxinc/nginx-unprivileged:stable-alpine
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1
