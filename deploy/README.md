# 腾讯云初版部署（Docker Compose）

单机部署两个常驻容器：`shiptrack-api`（Spring Boot API）与 `mysql:8.4`，
外加一个一次性迁移容器。`docker compose up` 先执行迁移，迁移成功退出后
API 才会启动；迁移失败时新版本 API 不会拉起。API 运行时只拿 DML 账号。

> 当前处于 M1 阶段：API 已能启动、连接数据库，但还没有对外 HTTP 接口。
> 验证方式以容器状态和启动日志为准。

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
| 入站 | 80/443 | 前置 HTTPS 反代 | 初版无对外接口，暂不开放 |
| 入站 | 8080 | **禁止开放** | API 只绑宿主机 `127.0.0.1`，公网走反代 |
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

# 3.2 启动迁移与 API（migrate 成功退出后 app 才会启动）
docker compose up -d
docker compose ps
docker compose logs --tail=50 migrate
docker compose logs --tail=50 app
```

`migrate` 退出码非 0 时，`app` 不会被创建或启动，`docker compose ps` 里看不到 `app`。
迁移日志出现 `Successfully applied`、应用日志出现 `Started ApiApplication` 即成功。

## 4. 验证

```bash
# 两个账号能否分别登录（各自密码从 .env 读取）
docker compose exec -T mysql sh -c \
  'MYSQL_PWD="$DB_APP_PASSWORD" mysql -u"$DB_APP_USER" -e "SELECT CURRENT_USER(), DATABASE()" "$MYSQL_DATABASE"'

# 迁移记录
docker compose exec -T mysql sh -c \
  'MYSQL_PWD="$DB_MIGRATION_PASSWORD" mysql -u"$DB_MIGRATION_USER" -e "SELECT version, success FROM flyway_schema_history" "$MYSQL_DATABASE"'
```

容器内部相互访问走 compose 网络（`mysql:3306`）。宿主机上不应出现 3306，
只应有 API 的回环端口：

```bash
ss -ltnp | grep -E ':8080|:3306'
# 期望仅看到 127.0.0.1:8080；出现 3306 或 0.0.0.0:8080 说明配置被改动
```

## 5. 升级

```bash
cd shiptrack && git pull
cd deploy
docker compose build
docker compose up -d    # 先跑 migrate，成功后才切换 app
```

镜像标签默认为 `local`；需要保留版本时在 `.env` 里设置 `APP_IMAGE_TAG=<版本>`，
构建前用 `docker compose build` 产出的镜像会自动带上该标签。

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
- API 只绑 `127.0.0.1`，公网访问必须经过前置 HTTPS 反代。
- 应用容器 `read_only` + `cap_drop: ALL` + `no-new-privileges`，仅 `/tmp` 可写。
- 后续接 GitHub Actions CD 时，SSH 私钥与主机信息放 Repo Secrets，不写进 workflow 文件。

## 8. 采集器（M2）

`collector` 模块已建好骨架，但它是独立镜像：需要 Chromium 与系统动态库，
不能和 API 塞进同一个镜像，也永远不持有数据库凭据。
M2 落地时新增 `deploy/Dockerfile.collector` 与独立的 compose 服务。

## 9. 故障排查

| 现象 | 排查 |
| --- | --- |
| `mysql` 一直 `starting` | `docker compose logs mysql`；首次初始化要建库建账号，属正常耗时 |
| 初始化日志出现 `must be different` | `DB_MIGRATION_*` 与 `DB_APP_*` 是同一个用户名或同一个密码；改成两套不同凭据后，空卷重试 |
| 迁移报 `access denied` | `.env` 的 `DB_MIGRATION_*` 与首次初始化时的值不一致；改过密码需重建卷或手动 `ALTER USER` |
| 迁移报 `requires spring.datasource.url` | compose 未传 `DB_URL`，用 `docker compose config -q` 校验配置（不要直接输出 `config`，会打印明文口令） |
| `app` 反复重启 | `docker compose logs app`；常见原因是 `DB_URL`/`DB_APP_*` 拼写错误 |
| 改了 `.env` 但没生效 | `docker compose up -d` 需要重建容器：`docker compose up -d --force-recreate app` |

> 注意：`mysql-init` 脚本只在数据卷为空时执行一次。初始化校验失败时数据卷
> 已不再为空，修正 `.env` 后需 `docker compose down -v` 才能让脚本重跑；
> 该命令会删除全部数据，仅在新机首启或确认无需保留数据时使用。
