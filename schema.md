# MongoDB Schema 设计

李麒 10245501423

| 项目 | 内容 |
|---|---|
| 文档版本 | **v2.0** |
| 版本日期 | 2026-10-29 |
| 对应阶段 | 第二阶段（需求变更） |
| 上一版本 | v1.0（第一阶段，2026-10-22） |
| 数据库 | `survey_db`，6 个集合 |
| 同步文件 | `db/schema.md`（与本文件内容完全一致，改一处须同步另一处） |
| 变更性质 | **增量**：v1.0 的集合、字段、索引一律保留，只新增 |

## 版本记录

| 版本 | 日期 | 阶段 | 主要变更 |
|---|---|---|---|
| v1.0 | 2026-10-22 | 第一阶段 | 初版。3 个集合（`users` / `surveys` / `responses`）、6 条索引、3 个集合级校验器 |
| v2.0 | 2026-10-29 | 第二阶段 | 新增 3 个集合（`questions` / `question_versions` / `question_shares`）与 9 条索引；`surveys.questions[]` 增 4 个可选字段、`responses.answers[]` 增 2 个可选字段；校验器扩到 6 个集合；新增 §11 版本演进 |

**向后兼容承诺**：v2.0 没有删除或修改 v1.0 的任何集合、字段与索引。v1.0 写入的文档在 v2.0 下可直接读写；新增字段全部可空，缺失时按 `local` / `null` 语义处理。逐字段对照见 §11。

## 目录

- [1. 集合总览](#1-集合总览)
- [2. users](#2-users)
- [3. surveys](#3-surveys)
- [4. responses](#4-responses)
- [5. questions（v2.0 新增）](#5-questionsv20-新增)
- [6. question_versions（v2.0 新增）](#6-question_versionsv20-新增)
- [7. question_shares（v2.0 新增）](#7-question_sharesv20-新增)
- [8. 索引（v2.0 由 6 条扩到 15 条）](#8-索引v20-由-6-条扩到-15-条)
- [9. 校验器（v2.0 由 3 个集合扩到 6 个）](#9-校验器v20-由-3-个集合扩到-6-个)
- [10. 设计说明](#10-设计说明)
- [11. 版本演进与兼容性](#11-版本演进与兼容性)

---

## 1. 集合总览

| 集合 | 职责 | 与其它集合的关系 |
|---|---|---|
| `users` | 用户账号 | `surveys.ownerId`、`responses.userId`、`questions.ownerId` 以字符串形式引用 `users._id` |
| `surveys` | 问卷定义（题目引用、内容快照、跳转规则内嵌） | 被 `responses.surveyId` 引用；`questions[].questionId` 引用 `questions._id` |
| `responses` | 答卷，同时作为答题会话（答案内嵌） | 引用一份 `surveys._id`；`answers[].libraryQuestionId` 引用 `questions._id` |
| `questions` | 题库中的题目身份（当前版本指针） | `currentVersionId` 指向 `question_versions._id` |
| `question_versions` | 题目版本，写入后不可修改 | 引用 `questions._id`；`parentVersionId` 自引用形成版本链 |
| `question_shares` | 题目共享授权 | 引用 `questions._id` 与两个 `users._id` |

三个宿主集合（`users` / `surveys` / `responses`）的生命周期与访问模式在第一阶段已经确定：
用户长期存在、问卷经历「草稿 → 发布 → 关闭」、答卷随填写不断新增；问卷整份读写、答卷逐题追加。
第二阶段新增的三个集合用于支撑「题目复用」，与宿主集合的关系是**引用**而不是内嵌：

- 题目身份是长期资产，会被多份问卷引用，不能内嵌进某一份问卷；
- 版本只增不改，是快照的来源，独立成集合才能保证不可变；
- 共享授权是按被授权方的反向查询，内嵌数组无法建索引支撑。

> 说明：每个文档都带一个 `_class` 字段（Spring Data 的类型提示），用于反序列化到具体实体类，属框架元数据，不参与业务。

---

## 2. users

```javascript
{
  _id: ObjectId,          // 主键
  username: String,       // 唯一，3-40 位，仅字母/数字/_/./-
  password: String,       // BCrypt 哈希（60 字符），不保存明文
  createdAt: Date         // 注册时间
}
```

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `_id` | ObjectId | 是 | 主键 | MongoDB 自动生成 |
| `username` | String | 是 | 唯一索引；长度 3–40；正则 `[A-Za-z0-9_.-]+` 全匹配 | 登录名 |
| `password` | String | 是 | 长度 60（BCrypt 固定输出） | 存 BCrypt 哈希，不可逆。用户**输入**的密码规则见 `RegisterRequest` 的 `@Size`：注册要求 8–16 位 |
| `createdAt` | Date | 是 | — | 注册时间 |

> 密码长度上限只作用于**注册**（`RegisterRequest`，8–16 位），不作用于登录。
> 第一阶段允许 8–72 位，存量账号中可能存在超过 16 位的密码；若在 `LoginRequest` 上同样加上限，
> 这些账号会直接无法登录。`LoginRequest` 只校验非空。

---

## 3. surveys

```javascript
{
  _id: ObjectId,
  ownerId: String,          // 创建者 users._id
  publicId: String | null,  // 发布后生成的分享短码；草稿为 null
  title: String,
  description: String | null,
  anonymous: Boolean,       // 是否匿名填写（不记录填写人身份）
  allowMultiple: Boolean,   // 是否允许同一人多次填写
  status: String,           // draft | published | closed
  deadline: Date | null,
  createdAt: Date,
  updatedAt: Date,
  questions: [ Question ]
}
```

### 3.1 Question（内嵌）

```javascript
{
  _id: String,              // 问卷内题目实例 ID（对应实体类的 id 字段，如 "q1"）
  order: Int,               // 展示顺序，发布时校验为正整数且不重复
  required: Boolean,        // 本问卷内是否必答
  type: String,             // single | multiple | text | number   ← 内容快照
  title: String,            //                                     ← 内容快照
  options: [ Option ],      // 选择题的选项；文本/数字题为空数组     ← 内容快照
  constraints: Constraints, // 可选                                  ← 内容快照
  jumps: [ JumpRule ],      // 可选，条件跳转规则（只属于本问卷）
  questionId: String|null,        // 题库题目身份；本地题目为 null
  questionVersionId: String|null, // 绑定的题库版本；本地题目为 null
  versionNo: Int|null,            // 绑定的版本号，冗余供展示
  sourceType: String              // local | library
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `_id` | String | 是 | **问卷内题目实例 ID**。语义与第一阶段完全一致：跳转规则、`answers[].questionId`、`visitedQuestionIds` 都通过它定位题目 |
| `order` | Int | 是 | 顺序，决定默认流向。属于问卷实例，不进入题库版本 |
| `type` | String | 是 | 四选一，枚举由校验器约束。引用题库题目时是版本内容的副本 |
| `title` | String | 是 | 1–1000 字。引用题库题目时是版本内容的副本 |
| `required` | Boolean | 是 | 本问卷内是否必答。引用题库题目且未显式指定时，取版本里的默认值 |
| `options` | Array\<Option\> | 是（可为空数组） | 选择题至少 2 个，发布时校验。选项 ID 随版本冻结 |
| `constraints` | Object \| null | 否 | 见 3.3 |
| `jumps` | Array\<JumpRule\> | 否 | 见 3.4。**只在问卷内存在，不进入题库版本** |
| `questionId` | String \| null | 否 | 题库题目身份；第一阶段文档与本地题目均无此字段 |
| `questionVersionId` | String \| null | 否 | 绑定的版本；决定这份问卷看到的内容 |
| `versionNo` | Int \| null | 否 | 冗余的版本号，供「被哪些问卷使用」直接展示 |
| `sourceType` | String | 否 | `local` / `library`。缺省视为 `local` |

### 3.2 Option（内嵌）

```javascript
{ _id: String, label: String, order: Int }
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `_id` | String | 是 | 选项 ID，跳转规则与答卷都通过它引用选项。**在同一版本内固定不变** |
| `label` | String | 是 | 1–500 字 |
| `order` | Int | 是 | 展示顺序 |

### 3.3 Constraints（内嵌，按题型使用不同子集）

| 字段 | 类型 | 适用题型 | 说明 |
|---|---|---|---|
| `minSelect` / `maxSelect` | Int | multiple | 最少/最多选几项；两者相等即「必须选 N 个」 |
| `minLength` / `maxLength` | Int | text | 文本长度区间 |
| `minValue` / `maxValue` | Double | number | 数值区间 |
| `isInteger` | Boolean | number | 是否必须是整数 |

### 3.4 JumpRule（内嵌，条件跳转）

```javascript
{ op: String, optionId: String | null, value: String | null, targetQuestionId: String }
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `op` | String | `eq` / `neq` / `contains` / `gt` / `gte` / `lt` / `lte` |
| `optionId` | String \| null | 选择题比较时使用 |
| `value` | String \| null | 文本/数字题比较时使用的字面值 |
| `targetQuestionId` | String | **本问卷内**另一道题的 ID，或 `END` 表示结束答题 |

规则按数组顺序匹配，**第一条命中的规则生效**；只允许向后跳转或跳到 `END`，发布时校验，避免死循环。

`targetQuestionId` 指向的是本问卷内的题目实例 ID，因此跳转规则无法随题目一起进题库：
同一道题被 A、B 两份问卷复用时，各自的跳转目标必然不同。

---

## 4. responses

```javascript
{
  _id: ObjectId,
  surveyId: String,              // 所属问卷 surveys._id
  userId: String,                // 非匿名答卷才有；匿名为 null 且不落库
  respondentKeyHash: String|null,// HMAC-SHA256(surveyId:userId)，用于匿名场景校验会话归属
  anonymous: Boolean,
  status: String,                // in_progress | submitted
  currentQuestionId: String,     // 服务端指定的当前题目，答题顺序以它为准
  submissionKey: String | null,  // allowMultiple=false 时的去重键，如 "user:<id>" / "anon:<hash>"
  startedAt: Date,
  submittedAt: Date | null,
  updatedAt: Date | null,
  visitedQuestionIds: [ String ],
  answers: [ Answer ]
}
```

### 4.1 Answer（内嵌）

```javascript
{
  questionId: String,             // 问卷内题目实例 ID
  libraryQuestionId: String|null, // 题库题目身份；本地题目与第一阶段历史答卷为 null
  questionVersionId: String|null, // 绑定的题库版本
  type: String,                   // 冗余题型，便于统计时无需回查 survey
  optionIds: [ String ],          // 选择题：选中的选项 ID
  textValue: String | null,       // 文本题
  numberValue: Double | null,     // 数字题，必须是原生数值
  answeredAt: Date
}
```

> `numberValue` 必须是 `double`（或 int/long/decimal），**不能是字符串**：字符串会让聚合 `$avg` 无法计算。
> 历史遗留的字符串数值由 `db/migrate-number-types.js` 一次性修正。

`questionId` 与 `libraryQuestionId` 的分工：前者用于「某份问卷内某道题」的统计（第一阶段的行为，完全不变），
后者用于「同一道题跨问卷」的归并。历史答卷没有后两个字段，跨问卷统计会自然排除它们，不需要清洗数据。

---

## 5. questions（v2.0 新增）

题库中的题目身份。一条记录代表「一道题」，不管它被多少份问卷引用。

```javascript
{
  _id: String,                    // 题目身份，短 ID
  ownerId: String,                // 创建者 users._id
  title: String,                  // 当前版本题干（冗余，供列表展示）
  type: String,                   // 当前版本题型（冗余，供筛选）
  currentVersionId: String,       // 指向 question_versions._id
  currentVersionNo: Int,          // 当前版本号（冗余）
  visibility: String,             // private | shared
  status: String,                 // active | archived
  sourceSurveyId: String | null,  // 来源问卷；题库内直接创建时为 null
  sourceQuestionInstanceId: String | null, // 来源问卷内题目实例 ID
  createdAt: Date,
  updatedAt: Date
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `_id` | String | 是 | 题目身份。所有版本、共享记录、问卷引用都以它为锚点 |
| `ownerId` | String | 是 | 创建者。**跨问卷统计的权限判定以此为准**：只有创建者能看该题在所有问卷上的聚合结果 |
| `title` / `type` | String | 是 | 当前版本的冗余副本，避免题库列表逐条回查版本集合 |
| `currentVersionId` | String | 是 | **「当前版本」的唯一判定依据**，不在版本文档上另设 `isCurrent` 标志，避免两处状态不一致 |
| `currentVersionNo` | Int | 是 | 当前版本号 |
| `visibility` | String | 是 | `private` / `shared`。共享明细在 `question_shares`，这里只是列表展示用的标志 |
| `status` | String | 是 | `active` / `archived`。归档而非删除：历史问卷与已发出的共享仍然有效 |
| `sourceSurveyId` / `sourceQuestionInstanceId` | String | 否 | 只有「从问卷加入题库」的题目才有值。二者与 `ownerId` 一起构成幂等键，使同一道问卷题重复点击「加入题库」不会产生重复题目。用「记录来源」而不是「回写问卷」来实现幂等，是为了守住「加入题库不修改原问卷」这条边界 |

---

## 6. question_versions（v2.0 新增）

题目版本。**只增不改**：任何内容修改都新增一条记录，旧版本永久保留。

```javascript
{
  _id: String,                     // 即问卷快照里的 questionVersionId
  questionId: String,              // 所属题目身份
  versionNo: Int,                  // 从 1 开始递增
  parentVersionId: String | null,  // v1 为 null
  restoredFromVersionId: String|null, // 仅「恢复旧版本」产生的版本才有值
  type: String,
  title: String,
  required: Boolean,               // 版本级默认必答性
  options: [ Option ],
  constraints: Constraints | null,
  changeNote: String | null,
  createdBy: String,
  createdAt: Date
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `_id` | String | 是 | 版本 ID，被 `surveys.questions[].questionVersionId` 引用 |
| `questionId` | String | 是 | 所属题目身份 |
| `versionNo` | Int | 是 | ≥ 1，与 `questionId` 组成唯一键，防止并发改题算出同一个版本号 |
| `parentVersionId` | String \| null | 否 | 上一版本，用于回答「这个题是从哪个版本改过来的」 |
| `restoredFromVersionId` | String \| null | 否 | 恢复旧版本时记录被恢复的版本，历史保持 append-only |
| `required` | Boolean | 是 | 版本级默认值，问卷内可覆盖 |
| `options` | Array\<Option\> | 是 | 选项 ID 在版本内固定，跳转规则与答卷都依赖它 |
| `changeNote` | String \| null | 否 | 改动说明 |
| `createdBy` | String | 是 | 创建该版本的用户 |

**刻意不包含的字段**：`jumps`（跳转目标指向本问卷内的题）与 `order`（展示顺序随问卷而变）。
这两者属于「问卷内的题目实例」，放在 `surveys.questions[]` 上。

---

## 7. question_shares（v2.0 新增）

题目共享授权。

```javascript
{
  _id: ObjectId,
  questionId: String,   // 被共享的题目身份
  ownerId: String,      // 授权者（冗余 questions.ownerId）
  granteeId: String,    // 被授权者 users._id
  permission: String,   // use | edit
  createdAt: Date
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `questionId` | String | 是 | 被共享的题目身份 |
| `ownerId` | String | 是 | 授权者，冗余保存以支撑「我共享出去的题目」列表 |
| `granteeId` | String | 是 | 被授权者，与 `questionId` 组成唯一键 |
| `permission` | String | 是 | `use` 只能读取并选入自己的问卷；`edit` 额外允许创建新版本 |
| `createdAt` | Date | 是 | 授权时间 |

共享的是**题目身份**而不是某个版本：被授权方选中该题时仍要指定具体版本，
因此创建者发布 v2 后，被授权方可以选择用新版还是继续用旧版。

---

## 8. 索引（v2.0 由 6 条扩到 15 条）

| 集合 | 索引名 | 键 | 类型 | 用途 |
|---|---|---|---|---|
| `users` | `uniq_username` | `{username: 1}` | 唯一 | 用户名唯一；并发注册由数据库兜底 |
| `surveys` | `uniq_survey_public_id` | `{publicId: 1}` | 唯一 + 部分（仅字符串） | 分享短码唯一；草稿的 `null` 互不冲突 |
| `surveys` | `idx_owner_created_at` | `{ownerId: 1, createdAt: -1}` | 普通 | 「我的问卷」按创建时间倒序 |
| `surveys` | `idx_survey_question_ref` | `{questions.questionId: 1}` | **多键** | 「某道题被哪些问卷使用」的反向查询 |
| `responses` | `uniq_survey_submission_key` | `{surveyId: 1, submissionKey: 1}` | 唯一 + 部分 | `allowMultiple=false` 时防并发重复答卷 |
| `responses` | `idx_survey_status` | `{surveyId: 1, status: 1}` | 普通 | 统计只取 `submitted` 答卷 |
| `responses` | `idx_survey_answer_question` | `{surveyId: 1, answers.questionId: 1}` | **多键** | 统计管道 `$unwind answers` 后按题过滤 |
| `responses` | `idx_answer_version_status` | `{answers.questionVersionId: 1, status: 1}` | **多键** | 跨问卷单题统计，按版本聚合 |
| `questions` | `idx_question_owner_updated` | `{ownerId: 1, updatedAt: -1}` | 普通 | 我的题库按最近修改倒序 |
| `questions` | `idx_question_owner_status` | `{ownerId: 1, status: 1, updatedAt: -1}` | 普通 | 按状态过滤后仍能直接用索引顺序，不内存排序 |
| `questions` | `uniq_question_source` | `{ownerId: 1, sourceSurveyId: 1, sourceQuestionInstanceId: 1}` | 唯一 + 部分（仅字符串） | 「从问卷加入题库」幂等；同一道问卷题不重复沉淀 |
| `question_versions` | `uniq_question_version_no` | `{questionId: 1, versionNo: 1}` | 唯一 | 同一道题的版本号不重复；并发改题由数据库兜底 |
| `question_shares` | `uniq_question_grantee` | `{questionId: 1, granteeId: 1}` | 唯一 | 同一人同一题只保留一条授权 |
| `question_shares` | `idx_grantee_created_at` | `{granteeId: 1, createdAt: -1}` | 普通 | 「别人共享给我的题目」按授权时间倒序 |
| `question_shares` | `idx_question_share_created` | `{questionId: 1, createdAt: -1}` | 普通 | 「我共享出去的授权记录」按授权时间倒序 |

索引由应用启动时的 `MongoIndexConfig` 创建（`readWrite` 权限即可）。第一阶段 6 个索引全部保留，第二阶段新增 9 个。

**三个部分唯一索引的意义**：草稿的 `publicId` 是 `null`、允许多次填写的答卷没有 `submissionKey`、
在题库里直接创建的题目没有来源 `sourceSurveyId`。普通唯一索引会让这些文档互相冲突
（多个 `null` 被视为重复），因此用 `partialFilterExpression` 只在字段为字符串时生效，
既保证唯一性又不误伤缺省值。

**三个多键索引的意义**：`answers` 与 `questions` 都是数组。查询「某个版本的所有答案」时，
多键索引让聚合只扫描包含该版本号的数组元素，而不是整份答卷或整份问卷。

---

## 9. 校验器（v2.0 由 3 个集合扩到 6 个）

由 `db/apply-validators.js` 应用（需要 `dbAdmin` 权限，属 schema 变更操作，故与应用的 `readWrite` 账号分离）：

- `validationLevel: "moderate"` —— 插入与「当前合法文档」的更新会被校验；历史不合法文档不被阻塞更新
- `validationAction: "error"` —— 非法写入直接失败（错误码 121）

校验内容举例：必填字段存在、`status`/`type`/`op`/`sourceType`/`permission` 只能取枚举值、
字符串长度区间、`numberValue` 必须是数值类型、`questions` 至少 1 项、`versionNo ≥ 1`。
这层校验与应用层 DTO 校验互补：DTO 拦住 HTTP 写入，校验器拦住绕过应用的写入。

第二阶段新增的字段在校验器里全部声明为**可空**，因此第一阶段的存量文档仍然满足校验器，
不需要先做数据迁移再启用。

---

## 10. 设计说明

### 10.1 为什么把题目拆成「身份」与「版本」

第一阶段题目是问卷文档里的内嵌对象，题目 ID 只在单份问卷内有意义。新需求要求：

- 同一道题被多份问卷使用；
- 修改题目时不影响已经发布的问卷；
- 能看到历史版本、能恢复旧版本；
- 同一道题的不同版本同时存在。

这四条合起来要求「题目」必须有**独立于任何问卷的生命周期**，于是拆成三层：

| 层 | 集合 | 变不变 | 承担什么 |
|---|---|---|---|
| 身份 | `questions` | 不变 | 这是哪一道题；谁创建的；当前指向哪个版本 |
| 版本 | `question_versions` | 只增不改 | 某个时刻的完整内容；被哪份问卷引用就锁定哪一份 |
| 实例 | `surveys.questions[]` | 随问卷变 | 在本问卷里的顺序、必答性、跳转规则，以及内容快照 |

「改题」因此被重新定义：不是原地修改，而是**新增一条版本记录**。旧问卷引用的是旧版本 ID，
内容永远不会变，这是「改题不影响已发布问卷」最直接的实现方式，不依赖任何额外的同步逻辑。

### 10.2 为什么问卷侧保留内容快照，而不是只存引用

如果问卷只存 `questionVersionId`，每次答题、每次统计都要回查版本集合：

- 答题链路每下发一题要查一次，一次答题会话几十次查询；
- 统计要先把问卷的题目 ID 映射成版本内容才能取选项文本；
- 「已发布问卷内容不变」变成依赖版本集合的不可变约定，而不是文档自身的事实。

保留快照后，答题与统计链路与第一阶段完全一致（只读问卷文档），「内容不变」由文档自身保证。
代价是数据冗余和「题库改题后草稿不会自动跟随」——后者恰好是期望行为：草稿要换版本应当显式操作，
而不是在用户不知情的情况下被换掉。

### 10.3 为什么跳转规则不进题库版本

`JumpRule.targetQuestionId` 指向的是**同一份问卷内**的另一道题。如果跳转规则随题目进入题库版本，
那么 A 问卷里「选 A 跳到第 5 题」的规则，被 B 问卷复用后会跳到 B 问卷的第 5 题，语义直接错乱。

因此题库版本只保存「这道题问什么、有哪些选项、有什么约束」，
跳转规则与展示顺序属于问卷实例配置，留在 `surveys.questions[]` 上。

### 10.4 跨问卷统计的权限边界

一道题被多个用户的问卷使用时，答卷数据属于各个问卷的创建者，但题目身份属于题目创建者。
如果把跨问卷聚合结果开放给所有使用者，等于让 A 看到 B 问卷的答卷分布。

因此跨问卷统计只对 `questions.ownerId` 开放，且只返回聚合结果（选项计数、平均值、文本条数），
不返回任何单份答卷的标识。单份问卷内的统计仍然只对 `surveys.ownerId` 开放，与第一阶段一致。

同一套边界也适用于「这道题被哪些问卷使用」：它暴露的是「谁在用我的题」，同样只给题目创建者。

### 10.4.1 三种角色能做什么

| 能力 | 题目创建者 | 被授权者（`use`） | 被授权者（`edit`） |
|---|---|---|---|
| 读取题目与版本历史 | 是 | 是 | 是 |
| 把版本选进自己的问卷 | 是 | 是 | 是 |
| 创建新版本（改题） | 是 | 否 | 是 |
| 归档题目 | 是 | 否 | 否 |
| 再共享给第三人 | 是 | 否 | 否 |
| 查看使用情况 / 跨问卷统计 | 是 | 否 | 否 |

`edit` 之所以不能归档、不能转授：归档会让创建者自己的题库少一道题，转授会让权限链失控
（创建者不知道自己的题被传到了谁手里）。这两项都是「处置权」，不是「使用权」。

撤销授权后，被授权者已有问卷里的题目快照不受影响——快照本来就独立于题库，
这正是「引用 + 快照」设计要解决的问题（见 10.2）。

### 10.5 为什么把题目、选项、跳转规则内嵌进问卷

- 一次填写问卷，必然要读它的全部题目、选项和跳转规则；内嵌后一次查询即可拿到，避免 N+1。
- 问卷的定义是整体变更的（改草稿是整份覆盖），不是单题独立演进，内嵌不会带来「部分更新」的复杂度。
- 单份问卷的题目量有天然上限（几十题），不会触发 16MB 文档限制。

> 第二阶段仍然保留这个内嵌结构，只是内嵌的内容从「题目本体」变成「引用 + 快照 + 问卷内配置」。
> 答题链路因此完全不需要改。

### 10.6 为什么把答案内嵌进答卷

- 一份答卷的答案在业务上是一个整体：交卷时整体标记状态，统计时按答卷聚合。
- 答题过程是「逐题追加」，`$push` 即可，天然适合文档模型。
- 单份答卷的答案数等于题目数，同样有上限。

### 10.7 为什么拆成多个集合而不是一个

- 生命周期不同：用户长期存在、问卷会经历状态流转、答卷持续新增、题目版本只增不改。
- 访问模式不同：问卷整份读写，答卷逐题追加，统计按问卷或按版本聚合，题库按创建者列表查询。
- 拆开后可以针对各自的查询建索引，也避免了单文档无限增长（版本链会一直增长，不能内嵌进题目身份）。

### 10.8 为什么不用关系型数据库

- 问卷结构是**可变 schema**：不同问卷的题目数量、题型、约束、跳转规则都不一样。关系模型下需要
  `surveys` / `questions` / `options` / `constraints` / `jumps` / `responses` / `answers` 至少 7 张表，
  且题目类型不同还要么留大量 NULL 列，要么再加「题型-属性」竖表，读一份问卷需要多表 JOIN。
  第二阶段再加上版本与共享，表数会到 10 张以上，且「题目版本」与「问卷引用」之间是多对多关系。
- 跳转规则是**树状条件结构**，在关系模型里只能序列化成字符串塞进一列，或用「规则表 + 条件表」多表表达，
  两者都不比文档直观。
- 答卷的答案结构随问卷而变，关系模型需要 `answers(question_id, option_id, text_value, number_value)`
  的宽表加大量 NULL；文档模型里答案就是数组元素，字段按题型出现。
- 本项目没有跨集合的强事务需求（创建问卷、答题、统计都是单文档或单集合聚合），不需要关系库的
  `ACID` 多表事务来换取建模复杂度。

### 10.9 为什么适合 MongoDB

- **聚合内嵌**匹配「整份问卷 / 整份答卷」的读写模式，一次查询取全，减少往返。
- **可变 schema** 让不同题型、不同约束共存于同一集合，不需要为每种题型建表；
  新增题库引用字段时，存量文档不需要迁移。
- **数组 + 多键索引 + 聚合管道**（`$unwind` / `$group` / `$avg`）直接支撑「按题统计」与「按版本跨问卷统计」。
- **部分唯一索引**能在数据库层表达「草稿的 publicId 可以为空、发布后必须唯一」这类条件约束。
- **唯一复合索引**（`{questionId, versionNo}`）能在数据库层兜住并发改题产生的版本号冲突。
- **原子单文档更新**（`{_id, status, currentQuestionId}` 条件更新）保证了并发答题时同一题不会被重复写入。

### 10.10 冗余的取舍

| 冗余点 | 为什么保留 |
|---|---|
| `answers[].type` 冗余题型 | 统计时按题型选择聚合方式，不必回查 `surveys`；题型在答卷生命周期内不会变 |
| `answers[].libraryQuestionId` / `questionVersionId` 冗余 | 跨问卷统计直接按答案聚合，不必先查问卷再映射；写入时从快照取值，不会与问卷不一致 |
| `surveys.questions[].type/title/options/constraints` 快照 | 答题与统计零关联查询；「已发布内容不变」由文档自身保证 |
| `questions.title/type` 冗余当前版本 | 题库列表直接展示与筛选，避免逐条回查版本集合 |
| `surveys.questions[].versionNo` 冗余版本号 | 「被哪些问卷使用」列表直接展示版本，不必回查 |
| `question_shares.ownerId` 冗余 | 支撑「我共享出去的题目」列表，避免回查题目集合 |
| `responses.anonymous` 与「`userId` 是否为空」语义重叠 | 显式字段可直接参与查询与索引，避免用 `userId: null` 做隐式判断 |
| `responses.respondentKeyHash` | 匿名答卷不能存 `userId`，但服务端仍需校验「这份答卷是不是你开的」，用确定性 HMAC 折中 |

---

## 11. 版本演进与兼容性

本节只回答一个问题：**从 v1.0 的库升级到 v2.0，哪些东西变了、哪些没变、旧数据会不会读不出来。**

### 11.1 版本基线对比

| 维度 | v1.0（第一阶段） | v2.0（第二阶段） | 变化 |
|---|---|---|---|
| 集合数 | 3 | 6 | +3（`questions`、`question_versions`、`question_shares`） |
| `users` 字段 | 4 | 4 | 无变化 |
| `surveys` 顶层字段 | 12 | 12 | 无变化 |
| `surveys.questions[]` 字段 | 8 | 12 | +4（全部可空） |
| `responses` 顶层字段 | 12 | 12 | 无变化 |
| `responses.answers[]` 字段 | 6 | 8 | +2（全部可空） |
| 索引数 | 6 | 15 | +9（既有 6 条定义不变） |
| 校验器覆盖集合 | 3 | 6 | +3 |
| 校验器必填字段 | 一阶段字段 | 一阶段字段 + 新集合字段 | 既有集合的必填列表未增加 |

### 11.2 既有集合的字段变更

新增字段全部**不在必填列表**里，因此 v1.0 的文档仍然满足 v2.0 的校验器，不需要先迁移再启用校验。

**`surveys.questions[]`（题目实例）**

| 字段 | 类型 | 可空 | v1.0 文档缺失时的行为 | 语义 |
|---|---|---|---|---|
| `questionId` | String | 是 | 按 `null` 处理 → 本地题目 | 引用题库时的题目身份 ID |
| `questionVersionId` | String | 是 | 按 `null` 处理 → 无绑定版本 | 引用的版本 ID，决定看到的内容 |
| `versionNo` | Int | 是 | 按 `null` 处理 | 冗余的版本号，供「被使用情况」直接展示 |
| `sourceType` | String | 是 | 按 `"local"` 处理 | `local` / `library` |

`sourceType` 是唯一一个「缺失与显式值语义不同」的字段：校验器把它声明为枚举 `local|library`，显式写 `null` 会被拒，而字段缺失时枚举校验不生效。迁移脚本把它统一补成 `"local"` 就是为了消除这个歧义。

**`responses.answers[]`（答案）**

| 字段 | 类型 | 可空 | v1.0 文档缺失时的行为 | 语义 |
|---|---|---|---|---|
| `libraryQuestionId` | String | 是 | 按 `null` 处理 → 本地题目的答案 | 该答案对应的题库题目 ID |
| `questionVersionId` | String | 是 | 按 `null` 处理 → 不参与跨问卷统计 | 该答案对应的题库版本 ID |

这两个字段是**跨问卷统计的分组键**。缺失时答案不会被统计进任何版本分组——这是 v1.0 答卷在 v2.0 下的正确行为，因为那些题目当时确实没有版本概念。

### 11.3 新增集合与既有数据的关系

| 新集合 | 是否由 v1.0 数据派生 | 说明 |
|---|---|---|
| `questions` | **否** | 题库从空开始。存量问卷里的题目不会自动导入——导入是用户的显式动作（`POST /api/surveys/{sid}/questions/{qid}/promote`） |
| `question_versions` | **否** | 只有 promote 或新建题库题目时才产生 v1 |
| `question_shares` | **否** | 共享关系是 v2.0 才有的概念，v1.0 不存在可迁移的等价数据 |

**为什么不做存量回填**：自动导入会替用户决定「哪些题值得进题库」，还会让后续手动导入撞上 `uniq_question_source` 唯一索引（同一道题被登记两次）。迁移脚本只负责建集合与补默认值，不制造业务数据。

### 11.4 兼容性矩阵

v1.0 文档在 v2.0 应用下的读取行为：

| 场景 | 涉及文档 | v2.0 的读取结果 | 是否需要迁移 |
|---|---|---|---|
| 打开一份 v1.0 草稿并修改 | `surveys` | 题目全部按 `local` 处理，可正常增删改、保存、发布 | 否 |
| 打开一份 v1.0 已发布问卷 | `surveys` | 正常展示与填写；`sourceType` 缺失按 `local` | 否 |
| 统计一份 v1.0 问卷 | `responses` | 整卷/单题统计口径不变 | 否 |
| 匿名答卷归属校验 | `responses` | `respondentKeyHash` 逻辑未变 | 否 |
| 跨问卷统计某道题 | `responses` | v1.0 答案无 `questionVersionId`，不进任何版本分组 | 否（预期行为） |
| 「这道题被哪些问卷使用」 | `surveys` | v1.0 题目无 `questionId`，不进入反向查询结果 | 否（预期行为） |
| 启用集合级校验器 | 全部 | `sourceType` 缺失时枚举不生效，可能通过校验；显式 `null` 会被拒 | **建议迁移** |
| 跨问卷统计的聚合写法 | `responses` | 需写成「字段不存在 **或** 为 `null`」两分支才不漏 | **建议迁移** |

结论：**不迁移也能跑，迁移后语义确定**。这正是迁移脚本存在的理由，也是它「可选但推荐」的原因。

### 11.5 升级路径

```
v1.0 数据库
   │
   ├─(A) 直接启动 v2.0 应用 ────────────────► 可用，但字段缺失/为 null 两种形态并存
   │
   └─(B) 执行 db/migrate-phase2.js ────────► 字段补齐为显式默认值，语义唯一
              │
              ├─ DRY_RUN=1 预演（只统计不写库）
              ├─ 自动备份到 db_backup_<时间戳>
              ├─ 建 3 个新集合 + 补 6 个字段默认值
              └─ 再执行一次 matched=0（幂等验证）
```

路径 (B) 的脚本特性与实测结果见 `db/migrate-phase2.js` 头部注释；脚本设计理由见 `系统文档-第二阶段.md` §2.14。

**回滚**：脚本不删除任何文档、不修改任何业务取值，只补字段与建集合。若要回到 v1.0 状态，从 `db_backup_<时间戳>` 还原即可；v1.0 应用会忽略新增字段，因此即使不还原，v1.0 应用也能继续读写。
