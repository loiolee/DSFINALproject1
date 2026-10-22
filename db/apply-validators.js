/*
 * 为三个集合建立 JSON Schema 校验器（集合级数据契约）。
 *
 * 为什么单独成脚本而不写在应用启动流程里：
 *   应用运行账号只需要 readWrite 权限；collMod 属于 schema 变更，需要 dbAdmin。
 *   让应用账号具备改结构的能力违反最小权限原则，因此把 DDL 交给管理员一次性执行。
 *
 * 用法（需要一个对 survey_db 具备 dbAdmin 或 dbOwner 权限的账号）：
 *   mongosh "mongodb://<admin>:<pwd>@localhost:27017/survey_db?authSource=admin" db/apply-validators.js
 *
 * 授予现有应用账号权限的方式（用管理员账号执行一次）：
 *   use admin
 *   db.grantRolesToUser("survey_app", [{ role: "dbAdmin", db: "survey_db" }])
 *
 * 脚本特性：
 *   1. 先做只读预检：统计现有数据中不符合 schema 的文档数量，避免上线校验器后旧数据写不进去
 *   2. validationLevel = moderate：插入与「当前合法文档」的更新会被校验，历史不合法文档不被阻塞
 *   3. validationAction = error：非法写入直接失败
 *   4. 幂等：重复执行只会覆盖同名校验器，不会报错
 */

const VALIDATION_LEVEL = "moderate";
const VALIDATION_ACTION = "error";

/* ==================== Schema 定义 ==================== */

const userSchema = {
  bsonType: "object",
  description: "用户账号：密码只保存 BCrypt 哈希，不保存明文",
  required: ["username", "password", "createdAt"],
  properties: {
    username: { bsonType: "string", minLength: 3, maxLength: 40, pattern: "^[A-Za-z0-9_.-]+$" },
    password: { bsonType: "string", minLength: 20, maxLength: 100 },
    createdAt: { bsonType: "date" }
  }
};

const optionSchema = {
  bsonType: "object",
  required: ["_id", "label", "order"],
  properties: {
    _id: { bsonType: "string" },
    label: { bsonType: "string", minLength: 1, maxLength: 500 },
    order: { bsonType: ["int", "long"] }
  }
};

const jumpSchema = {
  bsonType: "object",
  required: ["op", "targetQuestionId"],
  properties: {
    op: { enum: ["eq", "neq", "contains", "gt", "gte", "lt", "lte"] },
    optionId: { bsonType: ["string", "null"] },
    value: { bsonType: ["string", "null"] },
    targetQuestionId: { bsonType: "string" }
  }
};

const questionSchema = {
  bsonType: "object",
  required: ["_id", "order", "type", "title", "required"],
  properties: {
    _id: { bsonType: "string" },
    order: { bsonType: ["int", "long"] },
    type: { enum: ["single", "multiple", "text", "number"] },
    title: { bsonType: "string", minLength: 1, maxLength: 1000 },
    required: { bsonType: "bool" },
    options: { bsonType: "array", items: optionSchema },
    constraints: { bsonType: ["object", "null"] },
    jumps: { bsonType: "array", items: jumpSchema }
  }
};

const surveySchema = {
  bsonType: "object",
  description: "问卷：题目、选项、约束、跳转规则全部内嵌，跳转规则数据化而非写死在代码里",
  required: ["ownerId", "title", "status", "anonymous", "allowMultiple", "createdAt", "updatedAt", "questions"],
  properties: {
    ownerId: { bsonType: "string" },
    publicId: { bsonType: ["string", "null"] },
    title: { bsonType: "string", minLength: 1, maxLength: 200 },
    description: { bsonType: ["string", "null"], maxLength: 5000 },
    status: { enum: ["draft", "published", "closed"] },
    anonymous: { bsonType: "bool" },
    allowMultiple: { bsonType: "bool" },
    deadline: { bsonType: ["date", "null"] },
    createdAt: { bsonType: "date" },
    updatedAt: { bsonType: "date" },
    questions: { bsonType: "array", minItems: 1, items: questionSchema }
  }
};

const answerSchema = {
  bsonType: "object",
  required: ["questionId", "type", "answeredAt"],
  properties: {
    questionId: { bsonType: "string" },
    type: { enum: ["single", "multiple", "text", "number"] },
    optionIds: { bsonType: "array", items: { bsonType: "string" } },
    textValue: { bsonType: ["string", "null"] },
    // 必须是原生数值：字符串会让聚合 $avg 失效，见 db/migrate-number-types.js
    numberValue: { bsonType: ["double", "int", "long", "decimal", "null"] },
    answeredAt: { bsonType: "date" }
  }
};

const responseSchema = {
  bsonType: "object",
  description: "答卷：同时作为答题会话，answers 内嵌；匿名答卷不写 userId",
  required: ["surveyId", "anonymous", "status", "startedAt", "visitedQuestionIds", "answers"],
  properties: {
    surveyId: { bsonType: "string" },
    userId: { bsonType: ["string", "null"] },
    respondentKeyHash: { bsonType: ["string", "null"] },
    anonymous: { bsonType: "bool" },
    status: { enum: ["in_progress", "submitted"] },
    currentQuestionId: { bsonType: ["string", "null"] },
    submissionKey: { bsonType: ["string", "null"] },
    startedAt: { bsonType: "date" },
    submittedAt: { bsonType: ["date", "null"] },
    updatedAt: { bsonType: ["date", "null"] },
    visitedQuestionIds: { bsonType: "array", items: { bsonType: "string" } },
    answers: { bsonType: "array", items: answerSchema }
  }
};

const targets = [
  { collection: "users", schema: userSchema },
  { collection: "surveys", schema: surveySchema },
  { collection: "responses", schema: responseSchema }
];

/* ==================== 只读预检 ==================== */

print("=== 预检：现有数据是否满足 schema（只读）===");
let blocked = 0;
targets.forEach(({ collection, schema }) => {
  const total = db.getCollection(collection).countDocuments({});
  const matched = db.getCollection(collection)
    .aggregate([{ $match: { $jsonSchema: schema } }, { $count: "n" }])
    .toArray();
  const ok = matched.length ? matched[0].n : 0;
  const bad = total - ok;
  blocked += bad;
  print(`  ${collection}: 共 ${total} 份，符合 ${ok} 份，不符合 ${bad} 份`);
});
print(blocked === 0
  ? "预检通过：现有数据全部符合 schema，可以安全启用校验器"
  : `预检警告：有 ${blocked} 份历史文档不符合 schema，启用后它们仍可读，但更新会被拒绝（moderate 级别）`);

/* ==================== 应用校验器 ==================== */

print("=== 应用校验器 ===");
let unauthorized = 0;
targets.forEach(({ collection, schema }) => {
  try {
    db.runCommand({
      collMod: collection,
      validator: { $jsonSchema: schema },
      validationLevel: VALIDATION_LEVEL,
      validationAction: VALIDATION_ACTION
    });
    print(`  ${collection}: 已应用（level=${VALIDATION_LEVEL}, action=${VALIDATION_ACTION}）`);
  } catch (e) {
    if (e.code === 13 || e.codeName === "Unauthorized") {
      unauthorized += 1;
    } else {
      print(`  ${collection}: 失败 -> ${e.message}`);
    }
  }
});

if (unauthorized > 0) {
  print("");
  print(`当前账号缺少 dbAdmin 权限，${unauthorized} 个集合的校验器未应用。`);
  print("请用管理员账号执行一次授权后重新运行本脚本：");
  print('  use admin');
  print('  db.grantRolesToUser("<应用账号>", [{ role: "dbAdmin", db: "survey_db" }])');
} else {
  print("");
  print("完成。查看当前校验器：db.getCollectionInfos({name: \"surveys\"})[0].options.validator");
}
