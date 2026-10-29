# 基于 MongoDB 的在线问卷系统

Spring Boot + MongoDB 的在线问卷系统（后端 + 内置前端页面），包名 `com.example.surveysystem`，Java 21，默认端口 `8081`。

| 项目 | 内容 |
|---|---|
| 文档版本 | **v2.0** |
| 版本日期 | 2026-10-29 |
| 对应阶段 | 第二阶段（需求变更） |
| 上一版本 | v1.0（第一阶段，2026-10-22） |
| 变更性质 | **增量**：一阶段功能、接口、页面全部保留，只新增 |

## 版本记录

| 版本 | 日期 | 主要变更 |
|---|---|---|
| v1.0 | 2026-10-22 | 初版。3 个集合、16 个接口、29 条功能、5 个页面；一阶段集成测试 12 用例 |
| v2.0 | 2026-10-29 | 新增题库（3 个集合、17 个接口、8 条需求）；前端扩到 7 个视图；二阶段测试扩到 12 用例（必测项 6 + 风险回归 6）；新增迁移脚本 `migrate-phase2.js`；§1~§8 全量核对更新，新增 §9 版本升级指引 |

**v2.0 变更摘要（相对 v1.0）**

| 维度 | v1.0 | v2.0 | 说明 |
|---|---|---|---|
| 集合 | 3 | 6 | +`questions` / `question_versions` / `question_shares` |
| 索引 | 6 | 15 | +9，既有 6 条定义不变 |
| 接口 | 16 | 33 | +17（Q-01~Q-15、SQ-01~SQ-02） |
| 前端视图 | 5 | 7 | +题库、题目详情 |
| 后端 Java | 45 文件 / 2312 行 | 71 文件 / 5150 行 | 新增 26 文件，未删任何一阶段类或方法 |
| 测试用例 | 13（12+1） | 24 | +6 必测项重组、+5 二阶段回归 |
| 数据库脚本 | 3 | 4 | +`migrate-phase2.js` |

## 目录

- [1. 运行指引（环境与启动）](#1-运行指引环境与启动)
  - [1.1 环境要求与检查](#11-环境要求与检查)
  - [1.2 首次运行三步](#12-首次运行三步)
  - [1.3 启动：数据库未启用认证](#13-启动数据库未启用认证默认配置最快)
  - [1.4 启动：数据库已启用认证](#14-启动数据库已启用认证)
  - [1.5 配置项](#15-配置项)
  - [1.6 怎么判断启动成功](#16-怎么判断启动成功)
  - [1.7 打开页面并走一遍完整流程](#17-打开页面并走一遍完整流程5-步约-2-分钟)
  - [1.8 打包与运行 jar](#18-打包与运行-jar)
  - [1.9 运行测试](#19-运行测试)
  - [1.10 可选：应用数据库层校验器](#110-可选应用数据库层校验器需要-dbadmin-权限)
  - [1.11 常见问题](#111-常见问题)
  - [1.12 命令行快速自测（可选）](#112-命令行快速自测可选)
  - [1.13 提交前自测清单](#113-提交前自测清单)
- [2. 核心代码结构](#2-核心代码结构)
- [3. 统一返回格式与认证](#3-统一返回格式与认证)
- [4. API 清单](#4-api-清单)
- [5. 创建问卷示例](#5-创建问卷示例)
- [6. 单题答题示例](#6-单题答题示例)
- [7. MongoDB 设计要点](#7-mongodb-设计要点)
- [8. 前端页面与当前阶段说明](#8-前端页面与当前阶段说明)
- [9. 版本升级指引](#9-版本升级指引)

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
| `spring.data.mongodb.auto-index-creation` | —（可用 Spring Boot 宽松绑定 `SPRING_DATA_MONGODB_AUTOINDEXCREATION` 覆盖） | `true` | 启动时按 `MongoIndexConfig` 创建 15 个索引。设为 `false` 后不再自动创建，需手工建索引，否则部分唯一索引缺失、防重复注册与防重复答卷失去数据库兜底 |
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
2. 点「注册」，用户名至少 3 位、密码 8–16 位（例如 `demo001` / `password123`），注册成功后自动进入「我的问卷」。
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
./mvnw test -Dtest=RequiredAcceptanceTest          # 只跑六类必测项（第二阶段）
./mvnw test -Dtest=Phase2RegressionTest            # 只跑风险回归用例（第二阶段）
./mvnw test -Dtest=SurveyFlowIntegrationTest       # 只跑第一阶段功能用例
```

期望结果：

```
com.example.surveysystem.RequiredAcceptanceTest:      tests=6  errors=0 skipped=0 failures=0
com.example.surveysystem.Phase2RegressionTest:        tests=6  errors=0 skipped=0 failures=0
com.example.surveysystem.SurveyFlowIntegrationTest:   tests=12 errors=0 skipped=0 failures=0
com.example.surveysystem.SurveySystemApplicationTests: tests=1 errors=0 skipped=0 failures=0
```

耗时约 15 秒。三个测试类的分工：

| 测试类 | 组织方式 | 用例数 | 步骤数 | 数据前缀 |
|---|---|---|---|---|
| `RequiredAcceptanceTest` | 按课程「六、测试要求」的六类必测项组织：创建问卷 / 添加题目 / 跳转逻辑 / 校验 / 提交问卷 / 统计 | 6 | 222 | `acc_` |
| `Phase2RegressionTest` | 按风险角度组织：历史数据兼容 / 权限矩阵 / 版本隔离与换绑 / 引用只读与归档 / 前端契约 / 本地题目存入题库 | 6 | 124 | `reg_` |
| `SurveyFlowIntegrationTest` | 按第一阶段功能模块组织 | 12 | 101 | `itest_` |

三个类都自建独立账号与问卷，结束后按用户名前缀清理数据（第二阶段用例同时清理 `questions` / `question_versions` / `question_shares`），不影响已有数据。全部通过后即可认定「代码可运行」。

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
- [ ] `mvnw.cmd test` 6 + 12 + 1 个测试全部通过（六类必测项 + 第一阶段功能 + 上下文加载）
- [ ] 页面 7 个视图（登录注册、我的问卷、题库、题目详情、创建/编辑问卷、填写、统计）都能打开
- [ ] 题库页能新建题目、修改题目生成新版本、看版本历史与 diff、看引用列表与跨问卷统计
- [ ] 问卷编辑页能「从题库选题」，并能在题库出新版本后手动换绑
- [ ] 分享链接 `/survey/{publicId}` 能 302 跳转到填写页
- [ ] 关闭窗口后重新启动，数据仍在（说明真的写进了 MongoDB）

## 2. 核心代码结构

后端 Java 源码 71 个文件 / 5150 行。一阶段 45 个文件 / 2312 行，二阶段新增 26 个文件 / 2838 行，**没有删除任何一个一阶段类或方法**。

- `domain`：6 种 MongoDB 文档。第一阶段三种（`User`、`Survey`、`Response`）；第二阶段新增三种（`LibraryQuestion` 题目身份、`QuestionVersion` 不可变版本、`QuestionShare` 共享授权）。
- `dto`：22 个传输对象。一阶段 11 个；二阶段新增 11 个（`ConstraintsInput`、`OptionInput`、`QuestionContentRequest`、`ShareQuestionRequest`、`LibraryQuestionSummary`、`LibraryQuestionDetail`、`QuestionVersionView`、`QuestionVersionDiff`、`QuestionShareView`、`QuestionUsageView`、`CrossSurveyQuestionStats`）。答题题目视图仍作为 `StartResponseResult` 的内嵌类，避免将跳转规则暴露给前端。
- `repository`：6 个 `MongoRepository` 接口。一阶段 3 个；二阶段新增 `LibraryQuestionRepository`、`QuestionVersionRepository`、`QuestionShareRepository`。
- `service`：12 个类 = 8 个业务服务 + 4 个协作类。
  - 业务服务：`UserService`、`SurveyService`、`AnswerEngineService`、`StatsService`（一阶段）；`QuestionLibraryService`、`QuestionAccessService`、`QuestionShareService`、`CrossSurveyStatsService`（二阶段）。
  - 协作类：`LoginAttemptGuard`（登录限流）、`QuestionContentValidator`（引用与快照比对）、`QuestionContentMapper`（三种形态间转换）、`QuestionVersionComparator`（版本差异计算）。它们无状态或纯计算，不放进业务服务以避免职责混杂与循环依赖。
- `controller`：5 组 REST 接口——`AuthController`（注册登录）、`SurveyController`（问卷管理 + SQ-01/SQ-02）、`ResponseController`（答题）、`StatsController`（整卷/单题统计）、`QuestionController`（题库，二阶段新增，15 个接口）。
- `utils`：`JwtUtil`、`IdGenerator`、`HashUtils`。
- `exception`：统一业务异常及返回处理。
- `config`：`CorsConfig`（仅跨域）、`AuthInterceptor` + `WebMvcConfig`（JWT 认证拦截及放行规则）、`SecurityConfig`（`BCryptPasswordEncoder` Bean + 启动密钥检查）、`MongoIndexConfig`（15 条索引，定义变更时自动重建）。
- `db`：数据库侧脚本与文档——`schema.md`（集合结构、索引、设计说明）、`init-db.js`（初始化数据库账号）、`apply-validators.js`（六个集合的 JSON Schema 校验器）、`migrate-number-types.js`（一阶段数值类型迁移）、`migrate-phase2.js`（二阶段存量数据补齐）。
- `docs/images/`：系统文档插图（三层题目模型、共享权限矩阵、需求覆盖）。
- `tools/`：4 个 Python 冒烟脚本，直接打 HTTP 验证题库功能（`smoke-question-library.py`、`smoke-question-share.py`、`smoke-question-usage-stats.py`、`smoke-version-diff.py`）。
- 根目录文档：`系统文档-第二阶段.md`（本次主文档）、`系统文档.md`（一阶段，保留备查）、`测试用例-第二阶段.md`（二阶段 12 用例 / 346 步，必测项与风险回归合并为一份）、`测试用例-第一阶段.md`（一阶段 12 用例 / 101 步）、`schema.md`（与 `db/schema.md` 一致）。

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
| POST | `/api/questions` | 在题库新建题目，自动生成 v1 |
| GET | `/api/questions` | 我的题库列表，支持 `status=active\|archived\|all` 与 `keyword` |
| GET | `/api/questions/{questionId}` | 题目详情，含版本数与被引用次数 |
| PUT | `/api/questions/{questionId}` | 修改题目：追加一个新版本，旧版本保留 |
| DELETE | `/api/questions/{questionId}` | 归档题目（不物理删除） |
| GET | `/api/questions/{questionId}/versions` | 版本历史，最新在前 |
| GET | `/api/questions/{questionId}/versions/{versionNo}` | 指定版本内容 |
| GET | `/api/questions/{questionId}/versions/{from}/compare/{to}` | 对比两个版本的差异 |
| POST | `/api/questions/{questionId}/versions/{versionNo}/restore` | 以旧版本内容追加一个新版本 |
| POST | `/api/surveys/{surveyId}/questions/{questionId}/promote` | 把问卷里的本地题目存入题库 |
| PUT | `/api/surveys/{surveyId}/questions/{questionId}/version` | 手动把题目换绑到题库的另一个版本（仅草稿） |
| POST | `/api/questions/{questionId}/shares` | 把题目共享给指定用户，`permission` 为 `use` 或 `edit` |
| GET | `/api/questions/{questionId}/shares` | 我共享出去的授权记录 |
| DELETE | `/api/questions/{questionId}/shares/{granteeId}` | 撤销对某个用户的授权 |
| GET | `/api/questions/shared-with-me` | 别人共享给我的题目 |
| GET | `/api/questions/{questionId}/usages` | 这道题被哪些问卷使用（仅创建者） |
| GET | `/api/questions/{questionId}/stats` | 跨问卷单题统计（仅创建者，只返回聚合结果） |

上表前 16 项为第一阶段已有，后 17 项为第二阶段新增。题库接口全部挂在 `/api/questions` 下，因此与其它 `/api/**` 接口一样受 `AuthInterceptor` 保护。

**共享权限模型**：`use` 能读取题目、把任意版本选进自己的问卷；`edit` 在其基础上可以创建新版本。两者都**不能**转授给第三人、不能归档、不能查看使用情况与跨问卷统计——这三项是创建者独有。撤销授权不影响对方已有问卷里的题目快照（快照本来就独立于题库）。

`GET /api/questions/{questionId}` 会返回 `accessLevel` 字段（`owner` / `edit` / `use`），是页面裁剪按钮的唯一依据。之所以由服务端给结论：前端只知道用户名、拿不到自己的用户 ID，也没有共享记录的读取权，让页面比对 `ownerId` 等于把授权规则复制出第二份实现。

**版本升级策略**：问卷绑定题库版本后**不会自动跟随**。`GET /api/surveys/{surveyId}` 会为每个题库题目回填 `latestVersionNo` 与 `newerVersionAvailable` 两个字段（不落库，每次读取时现算），前端据此提示「该题已有新版本」。是否换绑由用户通过 `PUT /api/surveys/{surveyId}/questions/{questionId}/version` 显式决定，且只有草稿可以换绑——已发布问卷换绑会让同一份问卷的新旧答卷基于不同版本的选项，统计时无法区分。

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
- `surveys`：题目以「题库引用 + 内容快照 + 问卷内跳转规则」的形式内嵌；`publicId` 通过部分唯一索引保证只对已设置字符串值的文档唯一。
- `questions` / `question_versions` / `question_shares`：题库题目身份、不可变版本、共享授权。题目内容只在版本集合里保存一份，问卷侧保存引用与快照，因此题库改题不会影响已发布的问卷。
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
| `surveys` | `idx_survey_question_ref` | `questions.questionId: 1` | **多键** | 「某道题被哪些问卷使用」的反向查询 |
| `responses` | `idx_answer_version_status` | `answers.questionVersionId: 1, status: 1` | **多键** | 跨问卷单题统计，按版本聚合 |
| `questions` | `idx_question_owner_updated` | `ownerId: 1, updatedAt: -1` | 普通 | 我的题库按最近修改倒序 |
| `questions` | `idx_question_owner_status` | `ownerId: 1, status: 1, updatedAt: -1` | 普通 | 按状态过滤后仍能直接用索引顺序，不内存排序 |
| `questions` | `uniq_question_source` | `ownerId: 1, sourceSurveyId: 1, sourceQuestionInstanceId: 1` | 唯一 + 部分（仅字符串） | 「从问卷加入题库」幂等，同一道问卷题不重复沉淀 |
| `question_versions` | `uniq_question_version_no` | `questionId: 1, versionNo: 1` | 唯一 | 同一道题的版本号不重复，并发改题由数据库兜底 |
| `question_shares` | `uniq_question_grantee` | `questionId: 1, granteeId: 1` | 唯一 | 同一人同一题只保留一条授权 |
| `question_shares` | `idx_grantee_created_at` | `granteeId: 1, createdAt: -1` | 普通 | 「别人共享给我的题目」按授权时间倒序 |
| `question_shares` | `idx_question_share_created` | `questionId: 1, createdAt: -1` | 普通 | 「我共享出去的授权记录」按授权时间倒序 |

索引由 `MongoIndexConfig` 在启动时创建（`readWrite` 权限即可），共 15 个：第一阶段 6 个、第二阶段新增 9 个。其中三条是多键索引：`answers` 与 `questions` 是数组，MongoDB 会为数组中每个元素建立索引项，使「某问卷某道题」「某个版本的所有答案」「某道题被哪些问卷使用」这类聚合只扫描相关数组元素，而不是全集合扫描。`uniq_question_source` 用部分索引（只索引 `sourceSurveyId` 为字符串的文档）的原因是在题库里直接创建的题目没有来源，若建成普通唯一索引，第二条「直接创建」的题目会因 `null` 重复而插入失败。

**索引定义变更会自动重建**。直接调 `createIndex` 时，如果同名索引已存在但键不同，MongoDB 会抛 `IndexKeySpecsConflict`（错误码 86）导致**应用启动失败**；而且只在「跑过旧版本的库」上失败，新装的库一切正常，问题会拖到部署时才暴露。因此 `MongoIndexConfig.ensureIndex` 会先比对同名索引的键，不一致时 `dropIndex` 后重建，并在日志里打印变更前后：

```
WARN  c.e.s.config.MongoIndexConfig : 索引 idx_question_owner_status 的定义已变更，删除后重建：
      {"ownerId": 1, "status": 1} -> {"ownerId": 1, "status": 1, "updatedAt": -1}
```

只比较键、不比较 `unique` 等选项：选项变更会抛另一种错误（`IndexOptionsConflict`），属于需要人工确认的结构调整，自动重建反而有风险。

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

两个脚本，各自对应一次具体的结构变更，都保留、都不改写。合并成一个会让「从一阶段升到二阶段」和「从更早版本升到二阶段」两条路径共用一段无法同时满足的逻辑。

**`db/migrate-number-types.js`（一阶段）**：修正早期版本遗留的类型问题。早期用 `BigDecimal` 承载数值，在本环境下被序列化成字符串，导致聚合 `$avg` 失效。脚本把 `responses.answers[].numberValue`、`surveys.questions[].constraints.minValue/maxValue` 中的字符串转成原生 `double`，幂等且失败时保留原值。

```bash
mongosh "mongodb://<应用账号>:<密码>@localhost:27017/survey_db?authSource=survey_db" db/migrate-number-types.js
```

**`db/migrate-phase2.js`（二阶段）**：把一阶段存量数据补齐到题库结构。

```bash
# 预演
DRY_RUN=1 mongosh "mongodb://<应用账号>:<密码>@localhost:27017/survey_db?authSource=survey_db" db/migrate-phase2.js
# 执行
mongosh "mongodb://<应用账号>:<密码>@localhost:27017/survey_db?authSource=survey_db" db/migrate-phase2.js
```

不迁移其实也不会立刻报错（应用侧判空全是 null 安全），但有三处会真实出问题：

1. `surveys.questions[].sourceType` 在校验器里是枚举 `local|library`，显式写 `null` 会被拒，而字段缺失时枚举校验不生效——同一批数据「有时合法有时不合法」。
2. `responses.answers[]` 缺题库字段时，跨问卷统计必须写成「字段不存在 **或** 为 `null`」两种分支才不漏数据。
3. 三个新集合在纯一阶段库里不存在，直接跑 `apply-validators.js` 会在 `collMod` 阶段报 `ns does not exist`。

**关键实现细节**：三个缺口过滤器必须用 `$elemMatch`，不能写成 `{"answers.libraryQuestionId": {$exists:false}}`。数组路径上的 `$exists:false` 语义是「没有任何一个元素含该字段」，会漏掉「部分元素缺字段」的文档，并把 `answers: []` 误判为缺口（每次迁移都报缺口，永远补不完）。实测改为 `$elemMatch` 后第二次执行 `matched=0`，幂等成立。

**刻意不做**：不把存量问卷里的题目自动导入题库。自动导入会替用户决定「哪些题值得进题库」，还会让后续手动导入撞上 `uniq_question_source` 唯一索引。题库从空开始是符合预期的状态。

### 7.4 安全加固

- **登录失败限流**：同一用户名 5 分钟内失败 10 次后返回 `429`，抵御在线撞库（`LoginAttemptGuard`，单实例内存实现）。
- **消除用户名枚举的时间侧信道**：用户不存在时也执行一次 BCrypt 比对，使两条分支耗时接近。
- **启动密钥检查**：`SecurityConfig` 在启动时检测是否仍在使用 `application.yml` 里的演示密钥，是则打印告警；密钥为空直接拒绝启动。
- **统计响应上限**：文本回答明细最多返回 500 条，另用 `textAnswerCount` 给出总数，避免答卷很多时响应体无限膨胀。

## 8. 前端页面与当前阶段说明

`src/main/resources/static/` 下提供了一套无构建步骤的原生 HTML/CSS/JS 前端（多巴胺配色 + neo-brutalism 风格），启动后直接访问 `http://localhost:8081` 即可使用，共 7 个视图：

| 视图 | 路由 | 说明 |
|---|---|---|
| 登录 / 注册 | `#/login` | 登录后按 hash 参数回到原目标页 |
| 我的问卷 | `#/surveys` | 发布、改截止时间、关闭、删除草稿、复制分享链接；**有新版本的题目会打标** |
| 题库 | `#/library` | 我的题库 / 共享给我的；按状态与关键词筛选；新建、归档 |
| 题目详情 | `#/library/{id}` | 四个页签：版本历史（含版本 diff）、被哪些问卷使用、跨问卷统计、共享授权；后三个只对所有者显示 |
| 创建 / 编辑问卷 | `#/edit/{id}` | 4 种题型、选项、约束、跳转规则；**从题库选题**（引用即只读） |
| 填写问卷 | `#/fill/{publicId}` | 逐题作答、按规则跳转、校验失败即时提示 |
| 统计 | `#/stats/{id}` | 「整卷统计」与「按单题查看」两种视图 |

分享链接形如 `http://localhost:8081/survey/{publicId}`，由 `ResponseController` 302 重定向到 `index.html#/fill/{publicId}`。填写者需先登录；从分享链接进入时，登录成功后自动回到该填写页。

前端与后端的三处耦合点（页面依赖、但接口测试不会覆盖的字段）：

- `accessLevel`：题目详情与问卷详情返回，取值 `owner`/`edit`/`use`，页面据此裁剪「修改」「归档」「共享」按钮。**不由前端比对 `ownerId`**——JWT 只带用户名不带用户 ID，前端也没有共享记录的读取权。
- `sourceType`：`local`/`library`。页面据此把引用题渲染成只读卡片，不显示编辑控件。
- `newerVersionAvailable`：题库出了新版本后由服务端回填，页面显示「有新版本」提示与「换绑」按钮。系统**不自动升级**。

### 自动化测试

三套测试全部为 `@SpringBootTest` + 随机端口 + 真实 MongoDB，共 24 个业务用例 / 447 个步骤（另有 1 个 Spring 上下文加载测试）：

```bash
mvnw test                                        # 全部
mvnw test -Dtest=RequiredAcceptanceTest           # 课程必测六类，6 用例 222 步
mvnw test -Dtest=Phase2RegressionTest             # 二阶段风险回归，6 用例 124 步
mvnw test -Dtest=SurveyFlowIntegrationTest        # 一阶段功能，12 用例 101 步
```

| 测试类 | 覆盖内容 |
|---|---|
| `RequiredAcceptanceTest` | 创建问卷、添加题目、跳转逻辑、校验、提交问卷、统计（一个必测项一个用例） |
| `Phase2RegressionTest` | 历史数据兼容、权限矩阵、版本隔离与换绑、引用只读与归档、前端契约、本地题目存入题库 |
| `SurveyFlowIntegrationTest` | 一阶段 12 个功能模块 |

三个测试类都自建独立用户与问卷，结束时按用户名前缀清理数据（第二阶段用例同时清理 `questions` / `question_versions` / `question_shares`），不影响已有数据。步骤与预期输出见 `测试用例-第二阶段.md`、`测试用例-第一阶段.md`。

另有 4 个 Python 冒烟脚本（`tools/`），直接打 HTTP 验证题库功能，不依赖 JUnit：

```bash
python tools/smoke-question-library.py
python tools/smoke-question-share.py
python tools/smoke-question-usage-stats.py
python tools/smoke-version-diff.py
```

### 历史数据迁移

一阶段数据升级到二阶段需要执行一次迁移脚本（幂等，可重复执行；支持 `DRY_RUN=1` 预演，执行前自动备份到 `db_backup_<时间戳>`）：

```bash
# 预演：只统计要改什么，不写库
DRY_RUN=1 mongosh "mongodb://<应用账号>:<密码>@localhost:27017/survey_db?authSource=survey_db" db/migrate-phase2.js

# 正式执行
mongosh "mongodb://<应用账号>:<密码>@localhost:27017/survey_db?authSource=survey_db" db/migrate-phase2.js
```

脚本做三件事：创建 `questions` / `question_versions` / `question_shares` 三个集合；为 `surveys.questions[]` 补 `sourceType="local"`、`required`、`options`；为 `responses.answers[]` 补 `libraryQuestionId`/`questionVersionId = null`。**不把存量问卷里的题目自动导入题库**——导入是用户的显式动作（`POST /api/surveys/{id}/questions/{qid}/promote`）。

### 当前仍未包含的扩展功能

邮件找回密码、管理员角色、答卷导出（CSV/Excel）、答卷明细列表、令牌撤销与刷新令牌、题目版本的物理删除、已发布问卷的换绑、题库全文检索、共享授权的有效期与审批流。理由见 `系统文档-第二阶段.md` 1.12。

## 9. 版本升级指引

本节把「从 v1.0 升到 v2.0」要做的动作按顺序列全。全新的空库直接跳到 §1.2，不需要本节。

### 9.1 升级前

| 检查项 | 命令 / 动作 | 期望 |
|---|---|---|
| 现有集合 | `mongosh ... --eval "db.getCollectionNames()"` | 至少含 `users` / `surveys` / `responses` |
| 数据量 | `db.surveys.countDocuments()` / `db.responses.countDocuments()` | 记下来，升级后应完全一致 |
| 备份 | 迁移脚本会自动备份到 `db_backup_<时间戳>`；也可手工 `mongodump` | 备份存在且可读 |
| 代码版本 | `git log --oneline -1` | 一阶段最后一次提交（用于回滚） |

### 9.2 三条命令

```bash
# ① 预演：只统计要改什么，不写库
DRY_RUN=1 mongosh "mongodb://<应用账号>:<密码>@localhost:27017/survey_db?authSource=survey_db" db/migrate-phase2.js

# ② 正式执行（自动备份）
mongosh "mongodb://<应用账号>:<密码>@localhost:27017/survey_db?authSource=survey_db" db/migrate-phase2.js

# ③ 幂等验证：再跑一次应为 matched=0、零改动
mongosh "mongodb://<应用账号>:<密码>@localhost:27017/survey_db?authSource=survey_db" db/migrate-phase2.js
```

### 9.3 升级后验证

| 验证项 | 方式 | 期望 |
|---|---|---|
| 集合齐全 | `db.getCollectionNames()` | 6 个 |
| 索引齐全 | `db.questions.getIndexes()` 等 | 共 15 条 |
| 数据未丢 | `db.surveys.countDocuments()` | 与升级前一致 |
| 旧问卷可打开 | 登录后打开任一 v1.0 问卷 | 题目正常显示，可编辑草稿 |
| 旧答卷可统计 | 打开对应问卷统计页 | 数值与升级前一致 |
| 新功能可用 | 题库页新建一道题 | 生成 v1，可改出 v2 |
| 回归测试 | `mvnw test` | 25/25 通过 |

### 9.4 回滚

迁移脚本不删文档、不改业务取值，只补字段与建集合，因此有两条回滚路径：

1. **从备份还原**（彻底）：`mongorestore` 从 `db_backup_<时间戳>` 恢复 6 个集合。
2. **不还原**（够用）：直接跑 v1.0 代码。v1.0 的实体类不声明新增字段，Jackson 默认忽略未知字段，因此新字段不会导致反序列化失败。

### 9.5 文档版本对照

各文档共享同一套版本号，v2.0 的内容互相引用，改一处需要同步：

| 文档 | 版本 | 内容 | 与本文档的关系 |
|---|---|---|---|
| `README.md` | v2.0 | 环境、启动、接口清单、设计要点、升级指引 | 本文档 |
| `系统文档-第二阶段.md` | v2.0 | 系统说明、数据库设计、API、关键逻辑 | 设计的完整论证；本文档只给结论 |
| `schema.md` / `db/schema.md` | v2.0 | 集合字段级明细、索引、校验器、版本演进 | 字段的权威来源；两份内容必须一致 |
| `测试用例-第二阶段.md` | v2.0 | 二阶段 12 用例 / 346 步（必测项 6 + 风险回归 6） | 测试的详细步骤；八条新需求与边界风险的主要证据 |
| `测试用例-第一阶段.md` | v1.0 | 一阶段 12 用例 / 101 步 | 原样保留，作为回归基线 |
| `系统文档.md` | v1.0 | 一阶段完整文档 | 原样保留，备查 |

版本号规则：**主版本号跟阶段走**（v1.0 = 第一阶段，v2.0 = 第二阶段），小版本号留给同一阶段内的文档修订（如 v2.1 修错别字）。不因为「内容改得多」而跳主版本号——主版本号只表示对应的系统阶段。

## 排查 `PasswordEncoder` / `BCryptPasswordEncoder` 无法解析

本项目在 `pom.xml` 中直接声明了 `org.springframework.security:spring-security-crypto`，版本使用 Spring Boot 3.5.16 管理的 `${spring-security.version}`。如果 IDEA 仍提示 `org.springframework.security.crypto.password` 或 `org.springframework.security.crypto.bcrypt` 不存在：

1. 在 IDEA 中确认打开的是本工程的根目录（根目录应包含本 `pom.xml`），而不是旧版项目目录。
2. 在右侧 **Maven** 面板点击 **Reload All Maven Projects**，并确认没有启用 **Work offline**。
3. 在项目根目录终端执行 `mvn -U clean compile`。
4. 如需验证依赖是否真正进入编译 classpath，执行 `mvn dependency:tree -Dincludes=org.springframework.security:spring-security-crypto`，结果应包含 `org.springframework.security:spring-security-crypto:jar:6.5.11`。

注意：当前版本用 `CorsConfig.java` 处理 CORS、`AuthInterceptor` + `WebMvcConfig` 处理 JWT 拦截；`SecurityConfig.java` 只注册了一个 `BCryptPasswordEncoder` Bean，并未启用 Spring Security 过滤器链。不要为了获得 `PasswordEncoder` 而添加 `spring-boot-starter-security`，因为它会启用额外的 Web 安全自动配置，影响本项目现有的 JWT 拦截设计。
