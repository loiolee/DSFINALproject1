/*
 * 第二阶段数据迁移：把第一阶段的存量数据补齐到「题库 + 版本」的新结构。
 *
 * 背景
 * ----
 * 第二阶段在不改变第一阶段文档主结构的前提下，给内嵌对象增加了几个字段：
 *   surveys.questions[]  → sourceType / questionId / questionVersionId / versionNo
 *   responses.answers[]  → libraryQuestionId / questionVersionId
 * 并新增三个集合：questions（题目身份）、question_versions（不可变版本）、question_shares（共享授权）。
 *
 * 旧文档缺这些字段时，应用侧大多能按「本地题目」处理（判空逻辑都是 null 安全的），
 * 因此不迁移也不会立刻报错。但有三处会真实出问题，这正是本脚本存在的理由：
 *   1. 集合级校验器 db/apply-validators.js 的 sourceType 是枚举 local|library，
 *      显式写 null 会被拒；而字段缺失时枚举不生效，于是同一份数据「有时合法有时不合法」。
 *      迁移后旧数据一律是 local，语义确定。
 *   2. responses.answers[] 缺 libraryQuestionId / questionVersionId 时，聚合要写成
 *      「字段不存在 或 为 null」两种分支才不漏数据；统一补成显式 null 后，查询只需判 null。
 *   3. 三个新集合在纯第一阶段库里不存在，直接跑 apply-validators.js 会在 collMod 阶段
 *      报「ns does not exist」。本脚本负责把集合建出来。
 *
 * 刻意不做的事
 * ------------
 * 不把存量问卷里的题目自动导入题库。导入（POST /api/surveys/{id}/questions/{qid}/promote）
 * 是用户显式动作：自动导入会替用户决定「哪些题值得进题库」，并且会让后续手动导入撞上
 * uniq_question_source 唯一索引（同一道题被登记两次）。题目库从空开始是符合预期的状态。
 *
 * 用法（在项目根目录执行）
 * ----------------------
 *   # 先看会改什么，不写库
 *   DRY_RUN=1 mongosh "mongodb://<应用账号>:<密码>@localhost:27017/survey_db?authSource=survey_db" db/migrate-phase2.js
 *
 *   # 确认无误后正式执行
 *   mongosh "mongodb://<应用账号>:<密码>@localhost:27017/survey_db?authSource=survey_db" db/migrate-phase2.js
 *
 * 特性
 * ----
 *   - 幂等：所有 updateMany 的过滤条件都是「字段不存在」，重复执行 matched=0
 *   - 可预演：DRY_RUN=1 只统计不写库
 *   - 只补字段与建集合，不修改任何业务取值，不删除任何文档
 *   - 结束时输出校验报告；仍有不符合项会明确提示，不会假装成功
 */

const DRY_RUN = typeof process !== "undefined" && process.env && process.env.DRY_RUN === "1";

const NEW_COLLECTIONS = ["questions", "question_versions", "question_shares"];

/*
 * 三个「缺口」过滤器都必须写成 $elemMatch，不能用 {"questions.sourceType": {$exists:false}}。
 *
 * 数组路径上的 $exists:false 语义是「没有任何一个元素含该字段」，由此产生两个错误：
 *   1. 漏判：某份问卷 3 道题里只有 1 道缺 sourceType 时，另外 2 道有该字段，
 *      数组层级就认为字段存在，这份文档被跳过，漏补的题目在运行时按 local 处理却查不到。
 *   2. 误判：answers 为空数组（只建了答题会话、一题没答）的答卷，
 *      数组层级同样返回「没有该字段」，于是每次迁移都报有缺口，永远补不完。
 * $elemMatch 的语义是「至少有一个元素满足条件」，两个问题同时消失。
 */
const SURVEY_SOURCE_GAP = { questions: { $elemMatch: { sourceType: { $exists: false } } } };
const SURVEY_DEFAULT_GAP = {
  questions: { $elemMatch: { $or: [{ required: { $exists: false } }, { options: { $exists: false } }] } },
};
const ANSWER_FIELD_GAP = {
  answers: {
    $elemMatch: {
      $or: [{ libraryQuestionId: { $exists: false } }, { questionVersionId: { $exists: false } }],
    },
  },
};

function count(collection, filter) {
  return db.getCollection(collection).countDocuments(filter);
}

function line(title) {
  print("");
  print("── " + title + " " + "─".repeat(Math.max(0, 56 - title.length * 2)));
}

/* ==================== 0. 前置快照 ==================== */

line("前置检查");
print(`模式：${DRY_RUN ? "预演（DRY_RUN=1，不写库）" : "执行"}`);

const existing = db.getCollectionNames();
const missingCollections = NEW_COLLECTIONS.filter((c) => existing.indexOf(c) < 0);
print(`数据库：${db.getName()}`);
print(`已有集合：${existing.sort().join("、")}`);
print(`缺少的新集合：${missingCollections.length ? missingCollections.join("、") : "无"}`);

const surveyTotal = count("surveys", {});
const responseTotal = count("responses", {});
print(`问卷 ${surveyTotal} 份 / 答卷 ${responseTotal} 份`);

/* 每个缺口单独统计，便于判断「这次到底要改多少」 */
const gaps = {
  sourceType: count("surveys", SURVEY_SOURCE_GAP),
  defaults: count("surveys", SURVEY_DEFAULT_GAP),
  answers: count("responses", ANSWER_FIELD_GAP),
};

print("");
print("待补字段（单位：文档数，不是字段数）：");
print(`  surveys.questions[].sourceType            ${gaps.sourceType}`);
print(`  surveys.questions[].required / options    ${gaps.defaults}`);
print(`  responses.answers[] 的题库归属与版本字段  ${gaps.answers}`);

/* 脏数据只报告、不擅自修：非法 sourceType 说明有脚本或人工直接改过库，需要人工确认 */
const illegalSource = count("surveys", {
  "questions.sourceType": { $exists: true, $nin: ["local", "library"] },
});
if (illegalSource > 0) {
  print("");
  print(`[警告] ${illegalSource} 份问卷存在非法的 questions.sourceType，本脚本不处理，请人工确认：`);
  db.surveys
    .find({ "questions.sourceType": { $exists: true, $nin: ["local", "library"] } }, { title: 1, "questions.sourceType": 1 })
    .limit(5)
    .forEach((s) => print(`  - ${s.title}: ${JSON.stringify(s.questions.map((q) => q.sourceType))}`));
}

/* ==================== 1. 建缺失的集合 ==================== */

line("步骤 1／4 创建新集合");
if (!missingCollections.length) {
  print("三个集合都已存在，跳过");
} else if (DRY_RUN) {
  print(`[预演] 将创建：${missingCollections.join("、")}`);
} else {
  missingCollections.forEach((name) => {
    db.createCollection(name);
    print(`  已创建 ${name}`);
  });
  print("说明：集合级校验器与索引不在这里创建——校验器见 db/apply-validators.js，");
  print("      索引由应用启动时的 MongoIndexConfig 自动建立（auto-index-creation=true），");
  print("      两处各自维护一份规格，脚本里再抄一遍只会制造不一致。");
}

/* ==================== 2. 问卷内嵌题目补 sourceType ==================== */

line("步骤 2／4 surveys.questions[].sourceType 补 local");
if (gaps.sourceType === 0) {
  print("无缺口，跳过");
} else if (DRY_RUN) {
  print(`[预演] 将处理 ${gaps.sourceType} 份问卷`);
} else {
  const r = db.surveys.updateMany(SURVEY_SOURCE_GAP, [
    {
      $set: {
        questions: {
          $map: {
            input: "$questions",
            as: "q",
            in: {
              $cond: [
                { $eq: [{ $type: "$$q.sourceType" }, "missing"] },
                /* 默认值写在前面、原文档写在后面：$mergeObjects 后者覆盖前者，
                   万一字段已存在（理论上被过滤掉了）也不会被覆盖掉。 */
                { $mergeObjects: [{ sourceType: "local" }, "$$q"] },
                "$$q",
              ],
            },
          },
        },
      },
    },
  ]);
  print(`  matched=${r.matchedCount} modified=${r.modifiedCount}`);
}

/* ==================== 3. 问卷内嵌题目补 required / options ==================== */

line("步骤 3／4 surveys.questions[].required 与 options 补默认值");
print("防御性补齐：第一阶段写入时这两个字段必填，实测存量数据没有缺口，保留是为了");
print("容忍早期手工造数或第三方导入的文档。");
if (gaps.defaults === 0) {
  print("无缺口，跳过");
} else if (DRY_RUN) {
  print(`[预演] 将处理 ${gaps.defaults} 份问卷`);
} else {
  const r = db.surveys.updateMany(SURVEY_DEFAULT_GAP, [
      {
        $set: {
          questions: {
            $map: {
              input: "$questions",
              as: "q",
              in: {
                $mergeObjects: [
                  { required: true, options: [] },
                  "$$q",
                ],
              },
            },
          },
        },
      },
    ]
  );
  print(`  matched=${r.matchedCount} modified=${r.modifiedCount}`);
}

/* ==================== 4. 答卷答案补题库归属字段 ==================== */

line("步骤 4／4 responses.answers[] 补 libraryQuestionId / questionVersionId");
if (gaps.answers === 0) {
  print("无缺口，跳过");
} else if (DRY_RUN) {
  print(`[预演] 将处理 ${gaps.answers} 份答卷`);
} else {
  const r = db.responses.updateMany(ANSWER_FIELD_GAP, [
      {
        $set: {
          answers: {
            $map: {
              input: "$answers",
              as: "a",
              in: {
                /* $ifNull 把「字段不存在」统一成 null，再让原文档覆盖上来：
                   已有值的答案保持原样，只给旧答案补上显式 null。 */
                $mergeObjects: [
                  {
                    libraryQuestionId: { $ifNull: ["$$a.libraryQuestionId", null] },
                    questionVersionId: { $ifNull: ["$$a.questionVersionId", null] },
                  },
                  "$$a",
                ],
              },
            },
          },
        },
      },
    ]
  );
  print(`  matched=${r.matchedCount} modified=${r.modifiedCount}`);
}

/* ==================== 5. 校验报告 ==================== */

line("校验");
const after = {
  sourceType: count("surveys", SURVEY_SOURCE_GAP),
  defaults: count("surveys", SURVEY_DEFAULT_GAP),
  answers: count("responses", ANSWER_FIELD_GAP),
};

print("补字段后剩余缺口（必须全为 0）：");
print(`  surveys.questions[].sourceType            ${after.sourceType}`);
print(`  surveys.questions[].required / options    ${after.defaults}`);
print(`  responses.answers[] 的题库归属与版本字段  ${after.answers}`);

/* 空答卷不参与上面的判定，单独报出来，避免「缺口为 0」掩盖了「其实一份没答」 */
print(`  （另有 ${count("responses", { answers: [] })} 份答卷尚未作答，answers 为空数组，无需迁移）`);

/* 引用关系自洽性：library 题目必须带 questionId 与 questionVersionId，local 题目必须不带 */
const brokenRef = count("surveys", {
  questions: {
    $elemMatch: {
      sourceType: "library",
      $or: [{ questionId: { $in: [null] } }, { questionVersionId: { $in: [null] } }],
    },
  },
});
const leakedRef = count("surveys", {
  questions: {
    $elemMatch: { sourceType: "local", questionId: { $nin: [null] } },
  },
});
print("");
print("引用关系自洽性：");
print(`  标为 library 但缺 questionId/questionVersionId 的问卷：${brokenRef}`);
print(`  标为 local 却残留 questionId 的问卷：${leakedRef}`);

const libraryRefs = count("surveys", { "questions.sourceType": "library" });
print("");
print("第二阶段功能可用性：");
print(`  引用了题库版本的问卷：${libraryRefs} 份`);
print(`  题库题目：${count("questions", {})} 道 / 版本：${count("question_versions", {})} 个 / 共享授权：${count("question_shares", {})} 条`);

const clean =
  after.sourceType === 0 && after.defaults === 0 && after.answers === 0 && brokenRef === 0 && leakedRef === 0;

print("");
if (DRY_RUN) {
  print("预演结束，未写入任何数据。去掉 DRY_RUN=1 重新执行即可生效。");
} else if (clean) {
  print("迁移完成：存量数据已符合第二阶段结构。");
  print("后续建议：执行 db/apply-validators.js 应用集合级校验器（需要 dbAdmin 权限）。");
} else {
  print("迁移未完全成功：上表仍有剩余项，请勿直接上线，先核对原始数据。");
  quit(1);
}
