# 基于 MongoDB 的在线问卷系统

Spring Boot + MongoDB 的在线问卷系统（后端 + 内置前端页面），包名 `com.example.surveysystem`，Java 21，默认端口 `8081`。

## 1. 运行指引（环境与启动）

本节是从零把项目跑起来的完整步骤，按顺序照做即可。下文用 `<项目目录>` 指代克隆或解压后的项目根目录（即包含 `pom.xml` 的那一层），命令都在该目录下执行；启动成功后访问 `http://localhost:8081`。

默认端口 `8081`，默认数据库 `survey_db`。

### 1.1 环境要求与检查

| 组件 | 版本 | 检查命令 | 期望输出 |
|---|---|---|---|
| JDK | 21 | `java -version` | 含 `21` 的版本号 |
| MongoDB | 8.x，且已启动 | `mongosh --eval "db.runCommand({ping:1})"` | `{ ok: 1 }` |
| Maven | 3.10.0（由 Wrapper 自动下载） | 无需检查 | 首次运行联网下载 |

Maven **不需要单独安装**：项目自带 `mvnw`（Maven Wrapper），首次运行会自动下载。

`mongosh` 提示找不到命令时，说明它不在 `PATH` 里，用完整路径调用（通常在 `C:\Program Files\MongoDB\Server\8.x\bin\mongosh.exe`）；也可以跳过它——只有建账号和执行脚本时才需要，启动应用不需要。

Windows 下 MongoDB 未启动时，以管理员身份执行 `net start MongoDB`。

### 1.2 首次运行三步

| 步骤 | 做什么 | 命令 | 详见 |
|---|---|---|---|
| 1 | 准备数据库 | 数据库未启用认证：跳过；已启用认证：创建应用账号 | 1.3 / 1.4 |
| 2 | 启动应用 | `mvnw.cmd spring-boot:run` | 1.3 / 1.4 |
| 3 | （可选）应用集合级校验器 | `mongosh ... db/apply-validators.js` | 1.10 |

启动日志出现 `Started SurveySystemApplication` 后，浏览器访问 `http://localhost:8081` 即可。

### 1.3 启动：数据库未启用认证（默认配置，最快）

适用情况：MongoDB 是全新安装，没有建过账号，`mongosh` 直连就能进去。

项目默认连接串是 `mongodb://localhost:27017/survey_db`，不带账号密码，确认 MongoDB 已启动后直接运行：

```bat
cd /d <项目目录>
mvnw.cmd spring-boot:run
```

PowerShell 下写成 `.\mvnw.cmd spring-boot:run`，Git Bash 下写成 `./mvnw spring-boot:run`。

需要换数据库名、端口或连接池参数时，用 `MONGODB_URI` 环境变量覆盖，不必改代码，写法见 1.4.2。

### 1.4 启动：数据库已启用认证

#### 1.4.1 创建应用账号（只需做一次）

用安装 MongoDB 时创建的管理员账号执行初始化脚本。脚本默认创建应用账号 `survey_app`，只授予 `readWrite` 权限：

```bash
mongosh "mongodb://<管理员账号>:<管理员密码>@localhost:27017/admin?authSource=admin" db/init-db.js
```

期望输出：

```
已创建账号 survey_app，角色：readWrite
```

账号与密码在脚本开头以常量声明，可自行修改：

```javascript
const APP_USER = "survey_app";
const APP_PASSWORD = "survey_app_pwd";   // 首次部署请改成强密码
const APP_READ_WRITE_ONLY = true;        // 只给 readWrite，符合最小权限
```

#### 1.4.2 用环境变量传入带认证的连接串

PowerShell：

```powershell
cd <项目目录>
$env:MONGODB_URI="mongodb://survey_app:survey_app_pwd@localhost:27017/survey_db?authSource=survey_db"
.\mvnw.cmd spring-boot:run
```

命令提示符 cmd：

```bat
cd /d <项目目录>
set MONGODB_URI=mongodb://survey_app:survey_app_pwd@localhost:27017/survey_db?authSource=survey_db
mvnw.cmd spring-boot:run
```

Git Bash：

```bash
cd <项目目录>
MONGODB_URI="mongodb://survey_app:survey_app_pwd@localhost:27017/survey_db?authSource=survey_db" ./mvnw spring-boot:run
```

环境变量只在当前窗口有效，关掉窗口即失效，不影响系统其它程序。`MONGODB_URI` 的优先级高于 `application.yml`。

也可以直接双击项目根目录的 `run-backend.bat`（它会强制把端口设为 8081 并调用 Wrapper）。

### 1.5 配置项

配置文件 `src/main/resources/application.yml`，所有敏感项都支持用环境变量覆盖：

| 配置项 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| `server.port` | `SERVER_PORT` | `8081` | HTTP 端口。**注意**：Spring Boot 宽松绑定会让 `SERVER_PORT` 覆盖 `server.port`，若终端里存在该变量需显式指定 |
| `spring.data.mongodb.uri` | `MONGODB_URI` | `mongodb://localhost:27017/survey_db?maxPoolSize=50` | 数据库连接串。默认连接本机未启用认证的库；数据库启用认证时用环境变量传入带账号的串，此时 `authSource` 不能漏 |
| `spring.data.mongodb.auto-index-creation` | —（可用 Spring Boot 宽松绑定 `SPRING_DATA_MONGODB_AUTOINDEXCREATION` 覆盖） | `true` | 启动时按 `MongoIndexConfig` 创建 6 个索引。设为 `false` 后不再自动创建，需手工建索引，否则部分唯一索引缺失、防重复注册与防重复答卷失去数据库兜底 |
| `app.cors.allowed-origins` | `SURVEY_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` | 跨域来源，逗号分隔。内置前端与后端同源，默认值不影响本地使用 |
| `app.security.token-secret` | `SURVEY_TOKEN_SECRET` | 演示值 | JWT 签名密钥，**部署前必须替换** |
| `app.security.hmac-secret` | `SURVEY_HMAC_SECRET` | 演示值 | 匿名答卷 HMAC 密钥，**部署前必须替换** |
| `app.security.token-ttl-seconds` | — | `86400` | 令牌有效期（秒） |
| `logging.level.com.example.surveysystem` | — | `INFO` | 本项目包的日志级别；排查问题时改为 `DEBUG` 可看到更细的执行过程 |

上表为 `application.yml` 中的全部可调项；此外还有 `spring.application.name`（值 `survey-system`），仅用于日志与监控标识，一般无需修改。索引清单见 7.1 节。

> 启动时 `SecurityConfig` 会检查两个密钥：仍是演示值会打印 `WARN`，为空则直接拒绝启动。

### 1.6 怎么判断启动成功

控制台出现以下两行即为成功（顺序可能略有不同）：

```
Tomcat started on port 8081 (http) with context path '/'
Started SurveySystemApplication in 4.7 seconds
```

同时会出现两条 `WARN`：

```
app.security.token-secret 仍在使用 application.yml 中的演示密钥……
app.security.hmac-secret 仍在使用 application.yml 中的演示密钥……
```

这两条是**正常提示**，不是错误：本地开发用演示密钥即可，对外部署时才需要换成随机值。

### 1.7 打开页面并走一遍完整流程（5 步，约 2 分钟）

1. 浏览器打开 `http://localhost:8081`，出现登录页。
2. 点「注册」，用户名至少 3 位、密码至少 8 位（例如 `demo001` / `password123`），注册成功后自动进入「我的问卷」。
3. 点「创建问卷」，填标题，添加两道题（例如一道单选、一道多选），保存草稿。
4. 回到「我的问卷」，点「发布」，复制生成的分享链接（形如 `http://localhost:8081/survey/abc123`）。
5. 新开一个浏览器窗口打开分享链接，登录后逐题作答，交卷；回到「统计」页查看结果。

预期结果：统计页显示提交份数、每个选项被选人数（多选题为被选次数）、数字题平均值、文本题明细。

### 1.8 打包与运行 jar

```bash
./mvnw clean package -DskipTests        # 打包
java -jar target/survey-system-0.0.1-SNAPSHOT.jar
```

产物是一个约 31 MB 的可执行 jar，包含后端与前端页面，换机器只需 JDK 21 + MongoDB。指定端口：

```bat
java -jar target\survey-system-0.0.1-SNAPSHOT.jar --server.port=9090
```

jar 方式同样可以用 `MONGODB_URI` 覆盖连接串，写法与 1.3 节一致。

### 1.9 运行测试

测试使用真实 MongoDB（`@SpringBootTest` + 随机端口），需要数据库已启动：

```bash
./mvnw test                                        # 全部测试
./mvnw test -Dtest=SurveyFlowIntegrationTest       # 只跑接口集成测试
```

期望结果：

```
com.example.surveysystem.SurveyFlowIntegrationTest: tests=12 errors=0 skipped=0 failures=0
com.example.surveysystem.SurveySystemApplicationTests: tests=1 errors=0 skipped=0 failures=0
```

耗时约 11 秒。共 12 个集成用例、101 个测试步骤（注册登录与数据隔离 / 多题型建卷 / 发布前校验 / 逐题校验 / 条件跳转 / 提交与统计 / 匿名与多次填写 / 协议层错误码 / 生命周期与截止时间 / 登录限流 / 会话归属与恢复 / 统计分母受跳转影响）。测试自建 `itest_` 前缀的账号与问卷，结束后自动清理，不影响已有数据。全部通过后即可认定「代码可运行」。

### 1.10 可选：应用数据库层校验器（需要 dbAdmin 权限）

不执行也能正常运行，只是少了数据库层的结构约束。

```bash
mongosh "mongodb://<管理员账号>:<管理员密码>@localhost:27017/survey_db?authSource=admin" db/apply-validators.js
```

脚本会先做只读预检（统计现有文档中有多少不符合 schema），再应用校验器。若账号缺少权限，脚本会打印授权命令。

### 1.11 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| `Exception authenticating MongoCredential` | 数据库启用了认证，但默认连接串不带账号，或账号不存在 | 按 1.4.1 节创建应用账号，再按 1.4.2 节用 `MONGODB_URI` 传入带认证的连接串 |
| `Port 8081 was already in use` | 端口被占用 | `netstat -ano \| findstr :8081` 找到进程结束，或加 `--server.port=9090` |
| 端口不是 8081 | 终端里存在 `SERVER_PORT` 环境变量，覆盖了配置文件 | `set SERVER_PORT=`（cmd）或 `Remove-Item Env:SERVER_PORT`（PowerShell）清掉，或直接用 `run-backend.bat` |
| `not authorized on survey_db to execute command collMod` | 执行校验器脚本的账号没有 `dbAdmin` | 属正常限制，跳过 1.10 节即可，不影响功能 |
| 页面能打开但所有操作提示 401 | 未登录，或令牌已过期（默认 24 小时） | 重新登录 |
| 双击 `index.html` 打开后页面提示无法请求接口 | 地址栏是 `file://`，浏览器不允许跨协议请求 | 必须通过 `http://localhost:8081` 访问 |
| `mvnw.cmd` 报 `JAVA_HOME not found` | 未设置 `JAVA_HOME` | 设为 JDK 21 安装目录，例如 `set JAVA_HOME=C:\Program Files\Java\jdk-21` |
| cmd 窗口中文乱码 | 代码页不是 UTF-8 | 先执行 `chcp 65001` |
| 第一次运行卡在下载 Maven | Wrapper 在下载 Maven 3.10.0 | 首次需要联网，之后走本地缓存 |

### 1.12 命令行快速自测（可选）

不想点页面时，可用下面的命令验证接口。Windows 10 及以上自带 `curl`，在 cmd 中逐条执行：

```bat
rem 1 注册，记下返回的 token
curl -s -X POST http://localhost:8081/api/auth/register -H "Content-Type: application/json" -d "{\"username\":\"demo001\",\"password\":\"password123\"}"

rem 2 登录
curl -s -X POST http://localhost:8081/api/auth/login -H "Content-Type: application/json" -d "{\"username\":\"demo001\",\"password\":\"password123\"}"

rem 3 用上一步的 token 查看我的问卷（把 <TOKEN> 换成实际值）
curl -s http://localhost:8081/api/surveys/mine -H "Authorization: Bearer <TOKEN>"
```

第 3 条返回 `code` 为 200 且 `data` 为数组，说明「注册 → 登录 → 带令牌访问」这条链路是通的。更完整的接口调用序列（创建 → 发布 → 答题 → 交卷 → 统计）见第 5、6 节，其中每个请求体都可以直接复制使用。

### 1.13 提交前自测清单

- [ ] 按 1.3 节（无认证数据库）能启动成功
- [ ] 按 1.4 节（带账号数据库）能启动成功
- [ ] `mvnw.cmd test` 12 + 1 个测试全部通过
- [ ] 页面 5 个视图（登录注册、我的问卷、创建问卷、填写、统计）都能打开
- [ ] 分享链接 `/survey/{publicId}` 能 302 跳转到填写页
- [ ] 关闭窗口后重新启动，数据仍在（说明真的写进了 MongoDB）

## 2. 核心代码结构

- `domain`：`User`、`Survey`、`Response` 三种 MongoDB 文档。
- `dto`：11 个 DTO（`UpdateDeadlineRequest` 用于单独修改截止时间）；答题题目视图作为 `StartResponseResult` 的内嵌类，避免将跳转规则暴露给前端。
- `repository`：三个 MongoRepository 数据访问接口。
- `service`：`UserService`、`SurveyService`、`AnswerEngineService`、`StatsService`。
- `controller`：注册登录、问卷管理、答题和统计的四组 REST 接口。
- `utils`：`JwtUtil`、`IdGenerator`、`HashUtils`。
- `exception`：统一业务异常及返回处理。
- `config`：`CorsConfig`（仅跨域）、`AuthInterceptor` + `WebMvcConfig`（JWT 认证拦截及放行规则）、`SecurityConfig`（`BCryptPasswordEncoder` Bean + 启动密钥检查）、`MongoIndexConfig`（唯一索引及查询索引）。
- `db`：数据库侧脚本与文档——`schema.md`（集合结构、索引、设计说明）、`init-db.js`（初始化数据库账号）、`apply-validators.js`（集合级 JSON Schema 校验器）、`migrate-number-types.js`（历史数值类型迁移）。
- `docs`：提交用文档——`系统文档.md`（系统说明、数据库设计、API 说明、关键逻辑说明）、`测试用例.md`（12 个用例，含步骤/输入/输出/结果）、`项目完成报告.md`（项目目标、系统设计、AI 使用过程、测试结果、问题与解决）、`images/`（报告插图）。
- `tools`：`package_submission.py`，按提交结构生成 zip 提交包。
- `README.md`：第 1 节即运行指引（环境检查、两种启动方式、常见问题、提交前自测清单），第 2 至 8 节为代码结构、接口与设计说明。

## 3. 统一返回格式与认证

所有接口采用统一结构：

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

除注册、登录外，所有 `/api/**` 接口都要求登录。将注册/登录返回的 `data.token` 放到请求头：

```text
Authorization: Bearer <token>
```

本项目用 BCrypt 存储密码，使用 `JwtUtil` 生成和验证带过期时间的 HS256 JWT。当前未实现令牌撤销、刷新令牌和多端会话管理；实际生产系统可进一步接入完整的 Spring Security 认证方案。

## 4. API 清单

| 方法 | 路径 | 作用 |
|---|---|---|
| POST | `/api/auth/register` | 注册并返回登录令牌 |
| POST | `/api/auth/login` | 登录并返回令牌 |
| POST | `/api/surveys` | 创建草稿问卷 |
| GET | `/api/surveys/mine` | 当前用户的问卷列表 |
| GET | `/api/surveys/{surveyId}` | 查看自己创建的问卷 |
| PUT | `/api/surveys/{surveyId}` | 修改草稿问卷 |
| DELETE | `/api/surveys/{surveyId}` | 删除草稿问卷 |
| POST | `/api/surveys/{surveyId}/publish` | 校验并发布问卷，生成 `publicId` |
| POST | `/api/surveys/{surveyId}/close` | 关闭已发布问卷 |
| PUT | `/api/surveys/{surveyId}/deadline` | 修改截止时间，`null` 表示取消截止时间 |
| POST | `/api/surveys/{publicId}/start` | 开始或恢复答题会话 |
| POST | `/api/responses/{responseId}/answer` | 提交当前单题并取得下一题 |
| POST | `/api/responses/{responseId}/submit` | 交卷 |
| GET | `/api/surveys/{surveyId}/stats` | 问卷创建者查看整卷统计 |
| GET | `/api/surveys/{surveyId}/stats/{questionId}` | 问卷创建者查看单道题的统计 |
| GET | `/survey/{publicId}` | 公开短链接，302 重定向到前端填写页 |

### 4.1 错误码约定

所有失败响应仍使用统一结构 `{"code": <HTTP 状态码>, "message": "...", "data": null}`，`code` 与 HTTP 状态码保持一致，便于前端统一处理。

| 状态码 | 含义 | 典型场景 |
|---|---|---|
| 400 | 请求参数不合法 | 字段校验失败、`constraints` 越界、答案类型与题型不符、JSON 格式错误 |
| 401 | 未认证 | 未带 `Authorization: Bearer`、令牌无效或已过期 |
| 404 | 资源不存在 | 问卷/答卷/题目不存在、访问不存在的页面路径 |
| 405 | 方法不允许 | 路径存在但 HTTP 方法不对，`message` 中会给出该接口支持的方法 |
| 409 | 状态冲突 | 修改已发布问卷、跳题后按旧顺序作答、重复提交、`allowMultiple=false` 时二次填写 |
| 415 | 媒体类型不支持 | 写接口未使用 `application/json` |
| 429 | 请求过于频繁 | 同一用户名 5 分钟内登录失败 10 次 |
| 500 | 服务端异常 | 仅表示代码缺陷或数据库不可用，异常堆栈会写入 `ERROR` 日志，不返回给客户端 |

> 注：`/api/**` 下不存在的路径会先被认证拦截器拦成 `401` 而不是 `404`，避免向未登录者泄漏接口是否存在。

## 5. 创建问卷示例

为了让跳转规则能引用稳定的 ID，以下示例显式定义题目和选项 ID。请求中的 `jumps` 按数组顺序匹配，第一条命中规则优先；目标可以是后续题目 ID 或 `END`。

`POST /api/surveys`：

```json
{
  "title": "周末活动偏好调查",
  "description": "课程项目示例",
  "anonymous": true,
  "allowMultiple": false,
  "deadline": "2027-01-01T00:00:00Z",
  "questions": [
    {
      "id": "q1",
      "order": 1,
      "type": "single",
      "title": "你喜欢户外活动吗？",
      "required": true,
      "options": [
        { "id": "q1_yes", "label": "喜欢", "order": 1 },
        { "id": "q1_no", "label": "不喜欢", "order": 2 }
      ],
      "jumps": [
        { "op": "eq", "optionId": "q1_no", "targetQuestionId": "END" }
      ]
    },
    {
      "id": "q2",
      "order": 2,
      "type": "multiple",
      "title": "你喜欢哪些活动？",
      "required": true,
      "options": [
        { "id": "q2_walk", "label": "徒步", "order": 1 },
        { "id": "q2_cycle", "label": "骑行", "order": 2 },
        { "id": "q2_camp", "label": "露营", "order": 3 }
      ],
      "constraints": { "minSelect": 1, "maxSelect": 2 }
    },
    {
      "id": "q3",
      "order": 3,
      "type": "number",
      "title": "你每月愿意花费多少元？",
      "required": false,
      "constraints": { "minValue": 0, "maxValue": 5000, "isInteger": true }
    },
    {
      "id": "q4",
      "order": 4,
      "type": "text",
      "title": "其他建议",
      "required": false,
      "constraints": { "maxLength": 500 }
    }
  ]
}
```

创建成功后，从返回数据中取得问卷内部 `id`，调用 `POST /api/surveys/{id}/publish` 发布。成功响应中的 `publicId` 用于填写入口。

可用跳转操作符：

- `eq` / `neq`：等于或不等于；选项题通过 `optionId` 比较，文本/数字题通过 `value` 比较。
- `contains`：选项题判断是否选中 `optionId`，文本题判断是否包含 `value`。
- `gt` / `gte` / `lt` / `lte`：数字题比较，`value` 必须是数字。

发布前会校验题型、选项、ID、顺序、约束范围与跳转目标，并拒绝任何逆向跳转，以避免循环。

## 6. 单题答题示例

开始：`POST /api/surveys/{publicId}/start`

成功返回 `responseId` 和服务端指定的当前题目。每次只提交该题，不要从前端传入或自行计算下一题。

`POST /api/responses/{responseId}/answer` 的 JSON 形态取决于题型：

```json
{ "questionId": "q1", "answer": "q1_yes" }
```

```json
{ "questionId": "q2", "answer": ["q2_walk", "q2_camp"] }
```

```json
{ "questionId": "q3", "answer": 120 }
```

```json
{ "questionId": "q4", "answer": "希望增加亲子活动" }
```

非必答题可以传 `"answer": null` 或空值（文本题为空字符串，多选题为空数组）。服务端校验答案后，根据跳转规则返回 `nextQuestion`；开始答题时返回当前 `question`。返回 `completed: true` 时，调用 `POST /api/responses/{responseId}/submit` 交卷。

## 7. MongoDB 设计要点

- `users`：用户名唯一索引，密码为 BCrypt 哈希。
- `surveys`：题目、选项、约束和跳转规则内嵌；`publicId` 通过部分唯一索引保证只对已设置字符串值的文档唯一。
- `responses`：答案内嵌；匿名响应通过 `@Field(write = NON_NULL)` 不落库 `userId` 字段，只保存 `respondentKeyHash`；`(surveyId, submissionKey)` 对字符串值建立部分唯一索引，防止 `allowMultiple=false` 时并发重复创建。
- 匿名答卷不保存 `userId`，保存 `HMAC-SHA256(surveyId:userId)`；服务端通过重算 HMAC 校验答题会话归属。由于该值是确定性 HMAC，它仍可用于同一问卷内的同人匹配，因此并不等同于“无法关联的匿名数据”。
- 提交答案时通过 `{_id, status, currentQuestionId}` 条件更新，保证同一题不会因并发请求被重复写入。
- 统计只筛选 `status=submitted`；先 `unwind answers`，再按题目统计实际答题数。跳转略过的题目不会被计入其分母。

### 7.1 索引清单

| 集合 | 索引名 | 键 | 类型 | 用途 |
|---|---|---|---|---|
| `users` | `uniq_username` | `username: 1` | 唯一 | 用户名不可重复，并发注册由数据库兜底 |
| `surveys` | `uniq_survey_public_id` | `publicId: 1` | 唯一 + 部分（仅字符串） | 分享短码唯一，草稿的 `null` 互不冲突 |
| `surveys` | `idx_owner_created_at` | `ownerId: 1, createdAt: -1` | 普通 | 「我的问卷」按创建时间倒序 |
| `responses` | `uniq_survey_submission_key` | `surveyId: 1, submissionKey: 1` | 唯一 + 部分 | `allowMultiple=false` 时防并发重复答卷 |
| `responses` | `idx_survey_status` | `surveyId: 1, status: 1` | 普通 | 统计只取 `submitted` 答卷 |
| `responses` | `idx_survey_answer_question` | `surveyId: 1, answers.questionId: 1` | **多键** | 支撑统计管道 `$unwind answers` 后按题过滤 |

索引由 `MongoIndexConfig` 在启动时创建（`readWrite` 权限即可）。最后一条是多键索引：`answers` 是数组，MongoDB 会为数组中每个 `questionId` 建立索引项，使「某问卷某道题」的聚合只扫描相关数组元素，而不是全集合扫描。

### 7.2 集合级校验器（JSON Schema）

应用层的 DTO 校验只能拦住走 HTTP 的写入；校验器把数据结构契约下沉到数据库，使绕过应用的写入同样受约束。校验器不在应用启动时创建，而是由 `db/apply-validators.js` 由管理员执行一次：

```bash
mongosh "mongodb://<admin>:<pwd>@localhost:27017/survey_db?authSource=admin" db/apply-validators.js
```

原因是**最小权限原则**：应用运行账号只需要 `readWrite`，而 `collMod`（修改集合结构）需要 `dbAdmin`。让应用账号具备改结构的能力是不必要的风险。若现有账号缺少权限，脚本会打印授权命令：

```javascript
use admin
db.grantRolesToUser("survey_app", [{ role: "dbAdmin", db: "survey_db" }])
```

脚本特性：先做**只读预检**（统计现有文档中不符合 schema 的数量），再应用校验器；`validationLevel=moderate`（历史不合法文档不被阻塞更新）、`validationAction=error`（非法写入直接失败）。

### 7.3 数据迁移脚本

`db/migrate-number-types.js` 修正早期版本遗留的类型问题：早期用 `BigDecimal` 承载数值，在本环境下被序列化成字符串，导致聚合 `$avg` 失效。脚本把 `responses.answers[].numberValue`、`surveys.questions[].constraints.minValue/maxValue` 中的字符串转成原生 `double`，幂等且失败时保留原值。

```bash
mongosh "mongodb://<应用账号>:<密码>@localhost:27017/survey_db?authSource=survey_db" db/migrate-number-types.js
```

### 7.4 安全加固

- **登录失败限流**：同一用户名 5 分钟内失败 10 次后返回 `429`，抵御在线撞库（`LoginAttemptGuard`，单实例内存实现）。
- **消除用户名枚举的时间侧信道**：用户不存在时也执行一次 BCrypt 比对，使两条分支耗时接近。
- **启动密钥检查**：`SecurityConfig` 在启动时检测是否仍在使用 `application.yml` 里的演示密钥，是则打印告警；密钥为空直接拒绝启动。
- **统计响应上限**：文本回答明细最多返回 500 条，另用 `textAnswerCount` 给出总数，避免答卷很多时响应体无限膨胀。

## 8. 前端页面与当前阶段说明

`src/main/resources/static/` 下提供了一套无构建步骤的原生 HTML/CSS/JS 前端（多巴胺配色 + neo-brutalism 风格），启动后直接访问 `http://localhost:8081` 即可使用：

- 登录 / 注册页
- 我的问卷：发布、修改截止时间、关闭、删除草稿、分享链接（一键复制）
- 创建问卷：4 种题型、选项、选择数量/长度/数值约束、跳转规则可视化配置
- 填写问卷：逐题作答、按规则自动跳转、校验失败即时提示
- 统计页：支持「整卷统计」与「按单题查看」两种视图

分享链接形如 `http://localhost:8081/survey/{publicId}`，由 `ResponseController` 302 重定向到 `index.html#/fill/{publicId}`。按需求约定，填写者需要先登录；从分享链接进入时，登录成功后会自动回到该填写页。

已实现自动化集成测试 `src/test/java/com/example/surveysystem/SurveyFlowIntegrationTest.java`（`@SpringBootTest` + 随机端口 + 真实 MongoDB），覆盖需求要求的创建问卷、添加题目、跳转逻辑、校验、提交问卷、统计六类用例：

```bash
mvnw test -Dtest=SurveyFlowIntegrationTest
```

测试用例自建独立用户与问卷，并在结束时按用户名前缀清理数据，不影响已有数据。

当前仍未包含的扩展功能：邮件找回密码、管理员角色、导出报表（CSV/Excel）、答卷明细列表、令牌撤销与刷新令牌。

## 排查 `PasswordEncoder` / `BCryptPasswordEncoder` 无法解析

本项目在 `pom.xml` 中直接声明了 `org.springframework.security:spring-security-crypto`，版本使用 Spring Boot 3.5.16 管理的 `${spring-security.version}`。如果 IDEA 仍提示 `org.springframework.security.crypto.password` 或 `org.springframework.security.crypto.bcrypt` 不存在：

1. 在 IDEA 中确认打开的是本工程的根目录（根目录应包含本 `pom.xml`），而不是旧版项目目录。
2. 在右侧 **Maven** 面板点击 **Reload All Maven Projects**，并确认没有启用 **Work offline**。
3. 在项目根目录终端执行 `mvn -U clean compile`。
4. 如需验证依赖是否真正进入编译 classpath，执行 `mvn dependency:tree -Dincludes=org.springframework.security:spring-security-crypto`，结果应包含 `org.springframework.security:spring-security-crypto:jar:6.5.11`。

注意：当前版本用 `CorsConfig.java` 处理 CORS、`AuthInterceptor` + `WebMvcConfig` 处理 JWT 拦截；`SecurityConfig.java` 只注册了一个 `BCryptPasswordEncoder` Bean，并未启用 Spring Security 过滤器链。不要为了获得 `PasswordEncoder` 而添加 `spring-boot-starter-security`，因为它会启用额外的 Web 安全自动配置，影响本项目现有的 JWT 拦截设计。
