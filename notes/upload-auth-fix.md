# 上传前跳转登录修复（2026-09-05）

上传前置接口 `/api/v1/upload/generation` 依赖请求属性 `userId`，但组织授权过滤器遗漏该路径。现已补齐身份属性传递。线上旧版本日志可见该接口返回 400；尚未用有效登录会话核实其错误分派到 401 的完整链路。

已部署后端镜像 `nexusmind-backend:upload-auth-20260905`（`79db646b6560`），同时更新 `local` 标签。与原线上 JAR 比对，运行文件仅身份过滤器及其内部类改变；无数据库迁移。4 项身份传递回归测试通过。

上线核验：后端 healthy，重启次数 0，健康接口 UP，生产首页 HTTP 200，未认证的 generation 请求 HTTP 401。部署配置中的初始管理员凭据登录返回 401，因此尚未完成已登录上传验证。

回滚镜像为 `nexusmind-backend:rollback-before-upload-auth-20260905`：

```bash
sudo docker tag nexusmind-backend:rollback-before-upload-auth-20260905 nexusmind-backend:local
sudo docker compose --env-file .env.deploy.local -f docker-compose.deploy.yml up -d --no-deps --no-build backend
```

构建和验证产物保存在 `/tmp/nexusmind-upload-auth-release`，构建日志为 `/tmp/nexusmind-upload-auth-build.log`。
