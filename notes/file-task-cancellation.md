# 文件删除与任务取消

删除成功后，删除前的上传、解析、向量化、索引和图谱任务不得再写入数据。

实现：

- `file_task_generation` 保存每位所有者、每份文件的持久化代次。删除先提交撤销标记，清理成功后允许新代次上传；清理失败则保留撤销状态，允许重试删除。
- 前端先获取 `/api/v1/upload/generation?fileMd5=...`，将返回代次作为 `uploadGeneration` 传入分片和合并请求。删除会取消分片、合并请求；迟到的旧请求被拒绝。旧客户端无代次请求只兼容从未删除过的文件。
- 后端等待解析和向量模型时以约 100ms 周期检查取消。取消关闭请求、退出消费者并确认消息，不进入 Kafka 重试或死信队列。
- 切片、解析图片和检索索引写入与删除共用数据库行锁。已进入提交阶段的短写入允许先结束，再由删除清理，避免“检查通过后恰好删除”的竞争。上传和合并同样被代次锁保护。
- 删除清理上传分片、Redis 标记及原有文件资源。图谱调度取消该文档的排队任务并中断正在运行的请求。
- MinerU 网关保留 `/file_parse` 接口，一次只转发一个解析请求。客户端断连后终止该请求的解析子进程（先 TERM，最多等待 2 秒后 KILL），释放计算资源；网关本身继续运行。后续请求重新启动解析子进程，模型文件仍使用原有磁盘缓存。

边界：第三方向量/图谱 API 的请求可以断开，但不能保证供应商立即停止计算或停止计费。当前正在提交的存储写入需要结束后才能完成删除清理。MinerU 取消后下一请求有子进程和模型初始化开销。

部署必须同时更新后端、前端和 MinerU 镜像，并使用 `docker-compose.deploy.yml` 中的 `uvicorn cancellable_api:app` 启动命令。后端由 Flyway 执行 `V12__file_task_generation.sql`。直连未加网关的外部 MinerU 只能取消客户端等待，不能保证远端进程停止。

验证覆盖：持久化撤销、阻塞请求取消、Java HTTP 连接实际关闭、删除/写入竞争、旧上传拒绝、新上传接受、清理失败后重试、Kafka 不重试取消任务、图谱任务取消；网关使用真实 HTTP 断连和独立子进程验证，不依赖实际模型推理。

## 2026-09-05 上线记录

已更新 `nexusmind-backend`、`nexusmind-web`、`nexusmind-mineru`，Flyway 已成功执行 V12。三个服务均正常运行，后端与 MinerU 健康检查通过，重启计数均为 0。已从后端容器验证解析网关，从线上 Caddy 网络验证生产网页；前端实际入口为 `/assets/index-CAdLcuhL.js`，包含上传代次逻辑。

发布镜像：

- `nexusmind-backend:cancellation-20260905`（`73c09b58820d`）
- `nexusmind-web:cancellation-20260905`（`bcc592197f6a`）
- `nexusmind-mineru:cancellation-20260905`（`ce12d2177907`）

MinerU 本次发布复用原运行镜像的依赖层并加入网关，未重新下载模型。原运行镜像均保留了 `rollback-before-cancellation-20260905` 标签；回滚覆盖配置位于 `/tmp/nexusmind-cancellation-release/rollback.compose.yml`，包含原 MinerU 启动命令。V12 为新增表，回滚应用时无需删除该表。
