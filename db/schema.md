# MongoDB Schema 设计
李麒 10245501423

数据库：`survey_db`，共 3 个集合。所有业务数据都在 MongoDB 中，未使用任何关系型数据库。

- [1. 集合总览](#1-集合总览)
- [2. users](#2-users)
- [3. surveys](#3-surveys)
- [4. responses](#4-responses)
- [5. 索引](#5-索引)
- [6. 校验器](#6-校验器)
- [7. 设计说明](#7-设计说明)

---

## 1. 集合总览

| 集合 | 职责 | 与其它集合的关系 |
|---|---|---|
| `users` | 用户账号 | `surveys.ownerId`、`responses.userId` 以字符串形式引用 `users._id` |
| `surveys` | 问卷定义（题目、选项、约束、跳转规则全部内嵌） | 被 `responses.surveyId` 引用 |
| `responses` | 答卷，同时作为答题会话（答案内嵌） | 引用一份 `surveys._id` |

三者的生命周期不同：用户长期存在、问卷会经历「草稿 → 发布 → 关闭」、答卷随填写不断新增。
访问模式也不同：问卷是「整份读写」，答卷是「逐题追加」。这是拆成三个集合的依据。

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
| `password` | String | 是 | 长度 60（BCrypt 固定输出） | 存 BCrypt 哈希，不可逆。用户**输入**的密码规则是 8–72 位，见 `RegisterRequest` 的 `@Size` |
| `createdAt` | Date | 是 | — | 注册时间 |

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
  _id: String,              // 题目 ID（对应实体类的 id 字段，如 "q1"）
  order: Int,               // 展示顺序，发布时校验为正整数且不重复
  type: String,             // single | multiple | text | number
  title: String,
  required: Boolean,
  options: [ Option ],      // 选择题的选项；文本/数字题为空数组
  constraints: Constraints, // 可选
  jumps: [ JumpRule ]       // 可选，条件跳转规则
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `_id` | String | 是 | 题目 ID，跳转规则通过它引用题目 |
| `order` | Int | 是 | 顺序，决定默认流向 |
| `type` | String | 是 | 四选一，枚举由校验器约束 |
| `title` | String | 是 | 1–1000 字 |
| `required` | Boolean | 是 | 必答题在提交前必须有答案（被跳转略过的除外） |
| `options` | Array\<Option\> | 是（可为空数组） | 选择题至少 2 个，发布时校验 |
| `constraints` | Object \| null | 否 | 见 3.3 |
| `jumps` | Array\<JumpRule\> | 否 | 见 3.4 |

### 3.2 Option（内嵌）

```javascript
{ _id: String, label: String, order: Int }
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `_id` | String | 是 | 选项 ID，跳转规则与答卷都通过它引用选项 |
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
| `targetQuestionId` | String | 目标题目 ID，或 `END` 表示结束答题 |

规则按数组顺序匹配，**第一条命中的规则生效**；只允许向后跳转或跳到 `END`，发布时校验，避免死循环。

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
  questionId: String,
  type: String,                  // 冗余题型，便于统计时无需回查 survey
  optionIds: [ String ],         // 选择题：选中的选项 ID
  textValue: String | null,      // 文本题
  numberValue: Double | null,    // 数字题，必须是原生数值
  answeredAt: Date
}
```

> `numberValue` 必须是 `double`（或 int/long/decimal），**不能是字符串**：字符串会让聚合 `$avg` 无法计算。
> 历史遗留的字符串数值由 `db/migrate-number-types.js` 一次性修正。

---

## 5. 索引

| 集合 | 索引名 | 键 | 类型 | 用途 |
|---|---|---|---|---|
| `users` | `uniq_username` | `{username: 1}` | 唯一 | 用户名唯一；并发注册由数据库兜底 |
| `surveys` | `uniq_survey_public_id` | `{publicId: 1}` | 唯一 + 部分（仅字符串） | 分享短码唯一；草稿的 `null` 互不冲突 |
| `surveys` | `idx_owner_created_at` | `{ownerId: 1, createdAt: -1}` | 普通 | 「我的问卷」按创建时间倒序 |
| `responses` | `uniq_survey_submission_key` | `{surveyId: 1, submissionKey: 1}` | 唯一 + 部分 | `allowMultiple=false` 时防并发重复答卷 |
| `responses` | `idx_survey_status` | `{surveyId: 1, status: 1}` | 普通 | 统计只取 `submitted` 答卷 |
| `responses` | `idx_survey_answer_question` | `{surveyId: 1, answers.questionId: 1}` | **多键** | 统计管道 `$unwind answers` 后按题过滤 |

索引由应用启动时的 `MongoIndexConfig` 创建（`readWrite` 权限即可）。

**两个部分唯一索引的意义**：草稿的 `publicId` 是 `null`、允许多次填写的答卷没有 `submissionKey`。
普通唯一索引会让这些文档互相冲突（多个 `null` 被视为重复），因此用 `partialFilterExpression`
只在字段为字符串时生效，既保证唯一性又不误伤缺省值。

---

## 6. 校验器

由 `db/apply-validators.js` 应用（需要 `dbAdmin` 权限，属 schema 变更操作，故与应用的 `readWrite` 账号分离）：

- `validationLevel: "moderate"` —— 插入与「当前合法文档」的更新会被校验；历史不合法文档不被阻塞更新
- `validationAction: "error"` —— 非法写入直接失败（错误码 121）

校验内容举例：必填字段存在、`status`/`type`/`op` 只能取枚举值、字符串长度区间、`numberValue` 必须是数值类型、
`questions` 至少 1 项。这层校验与应用层 DTO 校验互补：DTO 拦住 HTTP 写入，校验器拦住绕过应用的写入。

---

## 7. 设计说明

### 7.1 为什么把题目、选项、跳转规则内嵌进问卷

- 一次填写问卷，必然要读它的全部题目、选项和跳转规则；内嵌后一次查询即可拿到，避免 N+1。
- 问卷的定义是整体变更的（改草稿是整份覆盖），不是单题独立演进，内嵌不会带来「部分更新」的复杂度。
- 单份问卷的题目量有天然上限（几十题），不会触发 16MB 文档限制。

### 7.2 为什么把答案内嵌进答卷

- 一份答卷的答案在业务上是一个整体：交卷时整体标记状态，统计时按答卷聚合。
- 答题过程是「逐题追加」，`$push` 即可，天然适合文档模型。
- 单份答卷的答案数等于题目数，同样有上限。

### 7.3 为什么拆成三个集合而不是一个

- 生命周期不同：用户长期存在、问卷会经历状态流转、答卷持续新增。
- 访问模式不同：问卷整份读写，答卷逐题追加，统计按问卷聚合。
- 拆开后可以针对各自的查询建索引，也避免了单文档无限增长。

### 7.4 为什么不用关系型数据库

- 问卷结构是**可变 schema**：不同问卷的题目数量、题型、约束、跳转规则都不一样。关系模型下需要
  `surveys` / `questions` / `options` / `constraints` / `jumps` / `responses` / `answers` 至少 7 张表，
  且题目类型不同还要么留大量 NULL 列，要么再加「题型-属性」竖表，读一份问卷需要多表 JOIN。
- 跳转规则是**树状条件结构**，在关系模型里只能序列化成字符串塞进一列，或用「规则表 + 条件表」多表表达，
  两者都不比文档直观。
- 答卷的答案结构随问卷而变，关系模型需要 `answers(question_id, option_id, text_value, number_value)`
  的宽表加大量 NULL；文档模型里答案就是数组元素，字段按题型出现。
- 本项目没有跨集合的强事务需求（创建问卷、答题、统计都是单文档或单集合聚合），不需要关系库的
  `ACID` 多表事务来换取建模复杂度。

### 7.5 为什么适合 MongoDB

- **聚合内嵌**匹配「整份问卷 / 整份答卷」的读写模式，一次查询取全，减少往返。
- **可变 schema** 让不同题型、不同约束共存于同一集合，不需要为每种题型建表。
- **数组 + 多键索引 + 聚合管道**（`$unwind` / `$group` / `$avg`）直接支撑「按题统计」这类需求。
- **部分唯一索引**能在数据库层表达「草稿的 publicId 可以为空、发布后必须唯一」这类条件约束。
- **原子单文档更新**（`{_id, status, currentQuestionId}` 条件更新）保证了并发答题时同一题不会被重复写入。

### 7.6 冗余的取舍

| 冗余点 | 为什么保留 |
|---|---|
| `answers[].type` 冗余题型 | 统计时按题型选择聚合方式，不必回查 `surveys`；题型在答卷生命周期内不会变 |
| `responses.anonymous` 与「`userId` 是否为空」语义重叠 | 显式字段可直接参与查询与索引，避免用 `userId: null` 做隐式判断 |
| `responses.respondentKeyHash` | 匿名答卷不能存 `userId`，但服务端仍需校验「这份答卷是不是你开的」，用确定性 HMAC 折中 |
