# 文件处理恢复修复（2026-09-05）

本次针对服务重启后重放旧 Kafka 消息、临时下载链接过期，以及旧失败状态覆盖实际索引结果的问题。

## 行为

- 新任务不再将临时签名下载链接作为处理依赖；消费者使用配置的 MinIO 客户端读取 `merged/<文件名>`，历史消息也使用同一路径。
- 主动提交和重试生成 `attemptId`。数据库行锁下认领当前版本；旧版本、已删除及终态任务不再执行。Kafka 自动重投保留原版本，可恢复 RUNNING 任务。
- `lastSuccessfulStage` 保存恢复断点，`currentStage` 记录真实执行阶段。重试保留数据库断点，不再优先使用消息中的旧 `resumeFromStage`。
- 只有实际切片数与已完成解析记录一致，才复用切片。索引恢复还校验切片 ID、文本、模型名称、权限和向量存在性；校验请求按 128 条分批。
- 索引文档 ID 根据文件、用户和切片生成，写入等待刷新。部分写入失败保留 ES 的具体错误。
- Kafka 关闭自动提交，逐条处理后确认；新消息按文件 MD5 分区。
- 保存底层异常类型和信息，隐藏 URL 查询参数；迟到失败不能覆盖成功或重建已删除状态。

V11 迁移只新增两个可空字段，并从历史阶段和切片统计回填断点。旧消息的空版本与迁移后的空版本兼容；主动重试后旧消息失效。

## 验证与部署

17 项定向回归测试全部通过，覆盖旧断点、终态保护、删除后的消息、切片缺失、错误索引、MinIO 读取、ES 部分失败与刷新。测试使用一个 Maven 容器、一个测试进程，禁用 JUnit 并行，容器内存限制 768 MiB、CPU 限制 1 核。

部署镜像：`nexusmind-backend:recovery-20260905`。
回滚镜像：`nexusmind-backend:rollback-before-recovery-20260905`。
部署直接使用测试阶段生成的 JAR，未再次编译。与原线上 JAR 比对，业务差异仅涉及本次文件处理修复及 V11 迁移。

回滚命令：

```bash
sudo docker tag nexusmind-backend:rollback-before-recovery-20260905 nexusmind-backend:local
sudo docker compose --env-file .env.deploy.local -f docker-compose.deploy.yml up -d --no-deps --no-build backend
```

回滚不删除新增字段或迁移记录。处理状态表部署前备份位于服务器 `/tmp/nexusmind-processing-status-before-recovery.sql`。

## 后续扩展边界

当前部署仍是单个后端消费者，使用 Kafka 重投恢复中断任务。本次没有引入多实例执行租约、心跳巡检或自动重投死信；也没有把现有端到端耗时拆分为排队与实际执行耗时。若扩展到多实例，需额外处理超过 Kafka poll 间隔时的并发执行隔离。

上线核验：生产容器健康检查为 `UP`，Docker 状态为 `healthy`；V11 迁移成功，原有两份文件仍为 SUCCEEDED。生产 JAR 的 SHA-256 与测试产物一致（`3cb88d8e67bbf15ffa1876c83073188b27d6a6de8ed8ab9759dd89f6c1bb4370`）。前端代理对未认证状态查询返回预期的 401。Kafka 消费者已重新加入消费组。
