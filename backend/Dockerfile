FROM node:22-slim
WORKDIR /app
COPY backend/package*.json ./
RUN npm ci --omit=dev && npm i -g tsx
COPY backend/ ./
COPY shared/ ../shared/
ENV NODE_ENV=production
EXPOSE 8080
CMD ["tsx", "src/index.ts"]
