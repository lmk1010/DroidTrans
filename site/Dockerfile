# 官网镜像：构建静态站，用 nginx 托管。部署在 droidtrans.mkstore.life。
#
#   docker build -t droidtrans-site ./site
#   docker run -d -p 8090:80 --restart unless-stopped --name droidtrans-site droidtrans-site
#
# 安装包和 latest.json 不在这个镜像里，它们在 R2（droid.mkstore.life）。
# nginx.conf 已经处理好：/latest.json 反代过去（避开 CORS），
# 安装包 302 跳过去（不占这台机器的带宽）。

FROM node:22-alpine AS build
WORKDIR /app
# 先只拷依赖清单，依赖没变时这层能命中缓存
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=3s \
  CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
