# 本地基础设施

该 Compose 只提供本地开发使用的 PostgreSQL 与 Redis。

```bash
docker compose -f deploy/local/compose.yaml up -d --wait
docker compose -f deploy/local/compose.yaml ps
```

连接参数：

```text
PostgreSQL: jdbc:postgresql://localhost:5432/kk_studio
User:       kk_studio
Password:   kk_studio

Redis:      redis://localhost:6379
```

停止服务但保留 PostgreSQL 数据：

```bash
docker compose -f deploy/local/compose.yaml down
```

删除本地数据：

```bash
docker compose -f deploy/local/compose.yaml down -v
```

Redis 在该环境中按非持久化 projection/notification 使用；其数据可以随容器重建而丢失。PostgreSQL volume 是本地 durable facts 的唯一持久化位置。
