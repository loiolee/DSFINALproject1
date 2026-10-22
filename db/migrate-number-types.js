/*
 * 一次性数据迁移：把历史上以「字符串」保存的数值字段转成 MongoDB 原生数字。
 *
 * 背景：早期版本用 BigDecimal 承载数值，Spring Data 在本环境下把 BigDecimal 序列化成了字符串，
 * 导致聚合管道 $avg 无法计算（统计页平均分恒为空）。实体字段改为 Double 后，新写入的数据已是
 * 原生 double，但旧数据仍是字符串，需要一次性修正。
 *
 * 用法（在项目根目录执行）：
 *   mongosh "mongodb://<应用账号>:<密码>@localhost:27017/survey_db?authSource=survey_db" db/migrate-number-types.js
 *
 * 特性：
 *   - 幂等：只处理 bsonType 为 string 的字段，重复执行不会产生副作用
 *   - 安全：$convert 使用 onError 保留原值，非法字符串不会被丢弃或写成 null
 *   - 只做类型转换，不改变任何业务字段的取值
 */

const beforeAnswers = db.responses.countDocuments({ "answers.numberValue": { $type: "string" } });
const beforeConstraints = db.surveys.countDocuments({
  $or: [
    { "questions.constraints.minValue": { $type: "string" } },
    { "questions.constraints.maxValue": { $type: "string" } }
  ]
});
print(`[before] 待迁移答卷 ${beforeAnswers} 份 / 问卷 ${beforeConstraints} 份`);

if (beforeAnswers > 0) {
  const result = db.responses.updateMany(
    { "answers.numberValue": { $type: "string" } },
    [{
      $set: {
        answers: {
          $map: {
            input: "$answers",
            as: "a",
            in: {
              $cond: [
                { $eq: [{ $type: "$$a.numberValue" }, "string"] },
                {
                  $mergeObjects: [
                    "$$a",
                    {
                      numberValue: {
                        $convert: {
                          input: "$$a.numberValue",
                          to: "double",
                          onError: "$$a.numberValue"
                        }
                      }
                    }
                  ]
                },
                "$$a"
              ]
            }
          }
        }
      }
    }]
  );
  print(`[responses] matched=${result.matchedCount} modified=${result.modifiedCount}`);
}

if (beforeConstraints > 0) {
  const result = db.surveys.updateMany(
    {
      $or: [
        { "questions.constraints.minValue": { $type: "string" } },
        { "questions.constraints.maxValue": { $type: "string" } }
      ]
    },
    [{
      $set: {
        questions: {
          $map: {
            input: "$questions",
            as: "q",
            in: {
              $cond: [
                { $eq: [{ $type: "$$q.constraints" }, "missing"] },
                "$$q",
                {
                  $mergeObjects: [
                    "$$q",
                    {
                      constraints: {
                        $mergeObjects: [
                          "$$q.constraints",
                          {
                            minValue: {
                              $cond: [
                                { $eq: [{ $type: "$$q.constraints.minValue" }, "string"] },
                                {
                                  $convert: {
                                    input: "$$q.constraints.minValue",
                                    to: "double",
                                    onError: "$$q.constraints.minValue"
                                  }
                                },
                                "$$q.constraints.minValue"
                              ]
                            },
                            maxValue: {
                              $cond: [
                                { $eq: [{ $type: "$$q.constraints.maxValue" }, "string"] },
                                {
                                  $convert: {
                                    input: "$$q.constraints.maxValue",
                                    to: "double",
                                    onError: "$$q.constraints.maxValue"
                                  }
                                },
                                "$$q.constraints.maxValue"
                              ]
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
              ]
            }
          }
        }
      }
    }]
  );
  print(`[surveys] matched=${result.matchedCount} modified=${result.modifiedCount}`);
}

const afterAnswers = db.responses.countDocuments({ "answers.numberValue": { $type: "string" } });
const afterConstraints = db.surveys.countDocuments({
  $or: [
    { "questions.constraints.minValue": { $type: "string" } },
    { "questions.constraints.maxValue": { $type: "string" } }
  ]
});
print(`[after] 剩余字符串数值：答卷 ${afterAnswers} 份 / 问卷 ${afterConstraints} 份`);
print(afterAnswers === 0 && afterConstraints === 0 ? "迁移完成" : "仍有非法字符串，请检查原始数据");
