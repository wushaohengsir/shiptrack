# 腾讯云部署（Docker Compose）

单机部署五个服务：`mysql:8.4`、一次性迁移 `migrate`、`app`（Spring Boot API）、
`collector`（Chromium 采集器）、`web`（nginx 托管的 SPA + `/api` 反代）。
`docker compose up` 先执行迁移，迁移成功退出后 API 才会启动；API 通过
`/api/v1/health` 健康检查后，`collector` 与 `web` 才会拉起。API 运行时只拿
DML 账号，采集器不持有任何数据库凭据。

```
mysql ──► migrate (一次性) ──► app ──► collector   (内部 API + X-Internal-Token)
                                 └──► web         (同源 /api 反代)
```

两个对外入口（API 8080、web 8088）都只绑定 `127.0.0.1`，公网必须经过宿主机的
HTTPS 反代。

## 1. 前置条件

- **Docker Engine 服务端 28.0.0+** 与 Docker Compose v2（`docker compose version`）
  - 28.0.0 之前在默认 bridge 下，发布到 `127.0.0.1` 的端口可能被同二层网络的主机访问；
    本部署依赖回环发布隔离 API，所以低于该版本拒绝上线。
- 建议 2 核 4 GB 起；镜像构建阶段需要能访问 Maven Central
- 一个专用部署账号（如 `deploy`），加入 `docker` 组，不要用 root 日常操作

```bash
# 校验服务端版本，必须 >= 28.0.0（看 Server 段，不是 Client）
docker version --format '{{.Server.Version}}'

# 以 deploy 用户执行
sudo usermod -aG docker deploy
```

> `docker` 组成员等同宿主机 root 权限，只授予部署账号，不要给普通账号加。

## 2. 腾讯云安全组

| 方向 | 端口 | 来源 | 说明 |
| --- | --- | --- | --- |
| 入站 | 22 | 管理与 CI 出口 IP | 不要对 `0.0.0.0/0` 开放 |
| 入站 | 80/443 | 前置 HTTPS 反代 | 对外入口只有它 |
| 入站 | 8080 | **禁止开放** | API 只绑宿主机 `127.0.0.1`，公网走反代 |
| 入站 | 8088 | **禁止开放** | web 只绑宿主机 `127.0.0.1`，公网走反代 |
| 入站 | 3306 | **禁止开放** | MySQL 不发布任何宿主端口 |
| 出站 | 443 | 任意 | Maven 构建、MSC 采集、飞书 OpenAPI |

## 3. 首次部署

```bash
git clone https://github.com/wushaohengsir/shiptrack.git
cd shiptrack
# 首发固定到复审通过的提交，不要直接跟随 main 的最新推送。
git checkout <复审通过的提交 SHA>
cd deploy

cp .env.example .env
chmod 600 .env
# 编辑 .env：每一个 change-me 都换成 openssl rand -base64 24 的输出。
# DB_MIGRATION_* 与 DB_APP_* 必须是两个不同的账号、两个不同的密码。

# 3.1 启动数据库（首次启动会执行 mysql-init/01-create-users.sh 建两个账号）
docker compose up -d mysql
docker compose ps          # 等到 mysql 显示 healthy

# 3.2 启动迁移、API、采集器与 web
#     （migrate 成功后 app 才启动；app healthy 后 collector 与 web 才启动）
docker compose up -d
docker compose ps
docker compose logs --tail=50 migrate
docker compose logs --tail=50 app
docker compose logs --tail=50 collector
docker compose logs --tail=50 web
```

`migrate` 退出码非 0 时，`app` 不会被创建或启动，`docker compose ps` 里看不到 `app`。
迁移日志出现 `Successfully applied`、应用日志出现 `Started ApiApplication` 即成功。
`app` 的健康检查探测 `GET /api/v1/health` 并要求返回体里 `status` 为 `ok`；
数据库不可达时该接口返回 `status: down`，`collector` 与 `web` 会保持等待。

## 4. 验证

```bash
# 两个账号能否分别登录（各自密码从 .env 读取）
docker compose exec -T mysql sh -c \
  'MYSQL_PWD="$DB_APP_PASSWORD" mysql -u"$DB_APP_USER" -e "SELECT CURRENT_USER(), DATABASE()" "$MYSQL_DATABASE"'

# 迁移记录
docker compose exec -T mysql sh -c \
  'MYSQL_PWD="$DB_MIGRATION_PASSWORD" mysql -u"$DB_MIGRATION_USER" -e "SELECT version, success FROM flyway_schema_history" "$MYSQL_DATABASE"'
```

容器内部相互访问走 compose 网络（`mysql:3306`、`app:8080`）。宿主机上不应
出现 3306，只应有 API 与 web 的回环端口：

```bash
ss -ltnp | grep -E ':8080|:8088|:3306'
# 期望仅看到 127.0.0.1:8080 与 127.0.0.1:8088；
# 出现 3306 或 0.0.0.0:8080/0.0.0.0:8088 说明配置被改动
```

链路冒烟（全部走宿主机回环）：

```bash
# API 存活且数据库正常
curl -fsS http://127.0.0.1:8080/api/v1/health

# SPA 首页由 nginx 托管
curl -fsS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8088/

# 经 nginx 反代到 API；无 Bearer 令牌时 401 也证明链路通
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8088/api/v1/orders

# 采集器启动并加载了状态映射表
docker compose logs --tail=20 collector | grep 'Loaded .* status mappings'
```

采集器空闲轮询不会打印日志（只有真正处理到任务时才输出
`Collector cycle finished, processed N task(s)`）。想看端到端领取，先在页面上
建单并点「刷新」，再 `docker compose logs -f collector`。

## 5. 升级

```bash
cd shiptrack && git pull
cd deploy
docker compose build
docker compose up -d    # 先跑 migrate，成功后才切换 app
```

镜像标签默认为 `local`；需要保留版本时在 `.env` 里设置 `APP_IMAGE_TAG=<版本>`，
`docker compose build` 产出的 `shiptrack-api`、`shiptrack-collector`、
`shiptrack-web` 三个镜像会带上同一标签。只重建前端时可单独执行
`docker compose build web && docker compose up -d web`。

## 6. 备份

```bash
# 每日逻辑备份，保留 7 天（示例，建议写入 cron）
docker compose exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --databases "$MYSQL_DATABASE"' \
  > "/var/backups/shiptrack-$(date +%F).sql"
```

备份文件含业务数据，权限设为 `600`，不要放进仓库。

## 7. 安全约定

- 真实密码只存在于 `deploy/.env`（`chmod 600`、git 已忽略），不进镜像、不进仓库、不进 CI 日志。
- 迁移账号与业务账号分离；API 不启用 Flyway，也没有 DDL 权限。
- MySQL 不发布任何宿主端口；`MYSQL_ROOT_HOST=localhost`，root 只能走容器内 socket。
- API 与 web 都只绑 `127.0.0.1`，公网访问必须经过前置 HTTPS 反代。
- 应用、采集器、web 三个容器均 `read_only` + `cap_drop: ALL` + `no-new-privileges`，
  仅 tmpfs 目录可写。
- 采集器以非 root 的 `pwuser` 运行，不持有数据库凭据，也不发布宿主端口。
- web 镜像构建期不注入任何令牌；前端登录令牌只存在于浏览器 `sessionStorage`。
- 后续接 GitHub Actions CD 时，SSH 私钥与主机信息放 Repo Secrets，不写进 workflow 文件。

## 8. 采集器（collector）

独立镜像（`mcr.microsoft.com/playwright/java:v1.64.0-noble` + Chromium），
与 API 分开构建：它需要 Chromium 和系统动态库，而且**永远不持有数据库凭据**。

- 只通过 compose 网络调用 `http://app:8080/internal/**`，携带 `INTERNAL_TOKEN`；
  没有宿主端口，也没有任何 `DB_*` 环境变量。
- 以镜像内的非 root 用户 `pwuser` 运行；根文件系统 `read_only`，`/tmp` 与
  `/home/pwuser` 挂 tmpfs；`cap_drop: ALL`。
- Chromium 需要更大的共享内存：`shm_size: 1gb`。Docker 默认 seccomp 拦截
  Chromium 需要的 `clone/unshare` 等系统调用，compose 挂载
  `collector/seccomp-chromium.json` 放行。
- `config/status-mappings.yaml` 只读挂到 `/app/config`；API 更新版本号后，
  采集器按 `MAPPING_RELOAD_INTERVAL_MS` 重新加载。

可调环境变量（写进 `deploy/.env`，均有默认值）：`COLLECTOR_POLL_INTERVAL_MS`
（默认 30000）、`COLLECTOR_JAVA_TOOL_OPTIONS`（默认 `-Xms128m -Xmx512m`）、
`TASK_CLAIM_TIMEOUT_MS`。

```bash
docker compose logs -f collector        # 观察轮询与采集
docker compose exec collector id        # 期望 uid=1000(pwuser)，不是 root
```

## 9. web（nginx + SPA）

前端构建成静态资源，由 `nginxinc/nginx-unprivileged:1.27-alpine` 托管（容器内
监听 8080，宿主 `127.0.0.1:${WEB_HOST_PORT:-8088}`）。构建参数
`VITE_API_MODE=http`、`VITE_API_BASE_URL=/api/v1` 在构建时注入，浏览器与 API
同源，不需要 CORS。

nginx 行为：

- `/api/` 原样反代到 `http://app:8080`，附带 `X-Forwarded-*`，响应不缓存；
- 其余路径 `try_files ... /index.html`，支持前端 history 路由；
- 带哈希的 `/assets/` 长缓存 30 天，`index.html` 不缓存；
- 基础安全头（`X-Content-Type-Options`、`X-Frame-Options`、`Referrer-Policy`）与 gzip；
- 容器 `read_only` + `cap_drop: ALL` + `no-new-privileges`，仅 `/tmp` 可写。

```bash
docker compose logs -f web
curl -fsS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8088/
```

## 10. 故障排查

| 现象 | 排查 |
| --- | --- |
| `mysql` 一直 `starting` | `docker compose logs mysql`；首次初始化要建库建账号，属正常耗时 |
| 初始化日志出现 `must be different` | `DB_MIGRATION_*` 与 `DB_APP_*` 是同一个用户名或同一个密码；改成两套不同凭据后，空卷重试 |
| 迁移报 `access denied` | `.env` 的 `DB_MIGRATION_*` 与首次初始化时的值不一致；改过密码需重建卷或手动 `ALTER USER` |
| 迁移报 `requires spring.datasource.url` | compose 未传 `DB_URL`，用 `docker compose config -q` 校验配置（不要直接输出 `config`，会打印明文口令） |
| `app` 反复重启 | `docker compose logs app`；常见原因是 `DB_URL`/`DB_APP_*` 拼写错误 |
| `app` 一直 `starting`，`collector`/`web` 不启动 | 健康检查要求 `/api/v1/health` 返回 `status: ok`；`docker compose logs app` 看数据库连接 |
| `web` 访问 `/api/` 返回 502 | `app` 未 healthy，或 nginx 反代名 `app:8080` 与实际服务名不一致 |
| `collector` 启动即退出、日志提到 `clone`/`unshare` | seccomp 文件未挂上：确认 `deploy/` 下存在 `../collector/seccomp-chromium.json` |
| 改了 `.env` 但没生效 | `docker compose up -d` 需要重建容器：`docker compose up -d --force-recreate app` |

> 注意：`mysql-init` 脚本只在数据卷为空时执行一次。初始化校验失败时数据卷
> 已不再为空，修正 `.env` 后需 `docker compose down -v` 才能让脚本重跑；
> 该命令会删除全部数据，仅在新机首启或确认无需保留数据时使用。
