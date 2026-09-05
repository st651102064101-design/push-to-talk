FROM node:24-alpine AS dependencies

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force

FROM node:24-alpine

LABEL org.opencontainers.image.title="PTT Relay Server" \
      org.opencontainers.image.version="1.3.1-test"

ENV NODE_ENV=production \
    PORT=8181

WORKDIR /app
COPY --from=dependencies --chown=node:node /app/node_modules ./node_modules
COPY --chown=node:node package.json package-lock.json index.js apns.js ./

USER node
EXPOSE 8181

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD node -e "const net=require('node:net');const socket=net.connect(8181,'127.0.0.1',()=>{socket.end();process.exit(0)});socket.on('error',()=>process.exit(1));setTimeout(()=>process.exit(1),2000)"

CMD ["node", "index.js"]
