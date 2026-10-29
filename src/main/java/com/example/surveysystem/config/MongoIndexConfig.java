package com.example.surveysystem.config;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * 声明项目依赖的索引：唯一索引、查询索引、多键索引。重复启动时 MongoDB 会复用同名同定义的索引。
 *
 * <p>集合级 JSON Schema 校验器（validator）不在这里创建，而是由 {@code db/apply-validators.js}
 * 由数据库管理员执行一次。原因是最小权限原则：应用运行账号只需要 {@code readWrite}，
 * 而 {@code collMod} 属于 schema 变更操作，需要 {@code dbAdmin}。把 DDL 交给应用账号
 * 会让应用具备修改数据结构的能力，是不必要的风险。</p>
 */
@Configuration
public class MongoIndexConfig implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(MongoIndexConfig.class);

    private final MongoTemplate mongoTemplate;

    public MongoIndexConfig(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 同一用户名只能注册一次。
        ensureIndex("users", 
                new Document("username", 1),
                new IndexOptions().unique(true).name("uniq_username"));

        // 仅对字符串 publicId 建唯一索引，草稿中缺省/null 的 publicId 不互相冲突。
        ensureIndex("surveys", 
                new Document("publicId", 1),
                new IndexOptions().unique(true).name("uniq_survey_public_id")
                        .partialFilterExpression(new Document("publicId", new Document("$type", "string"))));
        // 支撑「我的问卷」按创建者倒序列表。
        ensureIndex("surveys", 
                new Document("ownerId", 1).append("createdAt", -1),
                new IndexOptions().name("idx_owner_created_at"));

        // allowMultiple=false 时 submissionKey 有值；匿名和非匿名的键均由该索引防止并发重复创建。
        ensureIndex("responses", 
                new Document("surveyId", 1).append("submissionKey", 1),
                new IndexOptions().unique(true).name("uniq_survey_submission_key")
                        .partialFilterExpression(new Document("submissionKey", new Document("$type", "string"))));
        // 支撑统计「按 surveyId + status 过滤已提交答卷」。
        ensureIndex("responses", 
                new Document("surveyId", 1).append("status", 1),
                new IndexOptions().name("idx_survey_status"));
        // 多键索引：answers 是数组，统计管道先 $unwind 再按 answers.questionId 过滤，
        // 该索引让「某问卷某道题」的聚合只扫描相关数组元素，避免全集合扫描。
        ensureIndex("responses", 
                new Document("surveyId", 1).append("answers.questionId", 1),
                new IndexOptions().name("idx_survey_answer_question"));

        // ===== 第二阶段新增：题库 =====

        // 我的题库按最近修改倒序；status 过滤用于把归档题目排除在选题列表外。
        ensureIndex("questions", 
                new Document("ownerId", 1).append("updatedAt", -1),
                new IndexOptions().name("idx_question_owner_updated"));
        // 带 status 过滤的列表：索引里必须带上 updatedAt，否则「先按 status 过滤、再按 updatedAt 排序」
        // 会退化成内存排序（MongoDB 只在排序键是索引后缀时才能直接用索引顺序）。
        ensureIndex("questions", 
                new Document("ownerId", 1).append("status", 1).append("updatedAt", -1),
                new IndexOptions().name("idx_question_owner_status"));

        // 「从问卷加入题库」的幂等保障：同一份问卷里的同一道题只能有一条题目身份。
        // 用部分索引是因为在题库里直接创建的题目没有来源，两个字段都为 null，
        // 若建成普通唯一索引，第二条「直接创建」的题目就会因为 null 重复而插入失败。
        ensureIndex("questions", 
                new Document("ownerId", 1).append("sourceSurveyId", 1).append("sourceQuestionInstanceId", 1),
                new IndexOptions().unique(true).name("uniq_question_source")
                        .partialFilterExpression(new Document("sourceSurveyId", new Document("$type", "string"))));

        // 同一道题的版本号不能重复。并发「修改题目」时两个请求可能算出同一个下一个版本号，
        // 由该唯一索引作最终保障，服务端捕获 DuplicateKeyException 后重试。
        ensureIndex("question_versions", 
                new Document("questionId", 1).append("versionNo", 1),
                new IndexOptions().unique(true).name("uniq_question_version_no"));

        // 同一个人对同一道题只保留一条授权记录，重复共享直接覆盖。
        ensureIndex("question_shares", 
                new Document("questionId", 1).append("granteeId", 1),
                new IndexOptions().unique(true).name("uniq_question_grantee"));
        // 支撑「别人共享给我的题目」按授权时间倒序列表。
        ensureIndex("question_shares", 
                new Document("granteeId", 1).append("createdAt", -1),
                new IndexOptions().name("idx_grantee_created_at"));
        // 支撑「我共享出去的授权记录」按授权时间倒序列表。
        // uniq_question_grantee 的前缀虽然也是 questionId，但后面接的是 granteeId，
        // 无法提供 createdAt 的顺序，缺这条索引就会内存排序。
        ensureIndex("question_shares", 
                new Document("questionId", 1).append("createdAt", -1),
                new IndexOptions().name("idx_question_share_created"));

        // 多键索引：支撑「某道题被哪些问卷使用」的反向查询。
        // 第一阶段没有这个字段，历史文档缺少 questions.questionId 时不会进入该索引，属正常情况。
        ensureIndex("surveys", 
                new Document("questions.questionId", 1),
                new IndexOptions().name("idx_survey_question_ref"));

        // 多键索引：支撑跨问卷单题统计。管道先按 answers.questionVersionId 匹配再按 status 过滤，
        // 复合索引把「版本」放在前、答卷状态放在后，避免扫全部答卷。
        ensureIndex("responses", 
                new Document("answers.questionVersionId", 1).append("status", 1),
                new IndexOptions().name("idx_answer_version_status"));
    }

    /**
     * 创建索引；如果同名索引已存在但键不同，先删除再重建。
     *
     * <p>直接调 {@code createIndex} 会在这种情况下抛 {@code IndexKeySpecsConflict}（错误码 86），
     * 后果是<b>应用启动失败</b>——而且只在「已经跑过旧版本」的库上失败，
     * 新装的库一切正常，问题会拖到部署时才暴露。</p>
     *
     * <p>索引定义随代码演进是常态（例如给 {@code idx_question_owner_status} 补上 {@code updatedAt}
     * 以消除内存排序），所以这一步必须自动处理，而不是要求运维手工 drop。</p>
     *
     * <p>只比较键，不比较 {@code unique} 等选项：这些选项变更会抛另一种错误（{@code IndexOptionsConflict}），
     * 属于需要人工确认的结构调整，自动重建反而有风险。</p>
     */
    private void ensureIndex(String collectionName, Document keys, IndexOptions options) {
        MongoCollection<Document> collection = mongoTemplate.getCollection(collectionName);
        String name = options.getName();
        if (name != null) {
            for (Document existing : collection.listIndexes()) {
                if (!name.equals(existing.getString("name"))) {
                    continue;
                }
                Object existingKey = existing.get("key");
                if (existingKey instanceof Document key && !key.equals(keys)) {
                    log.warn("索引 {} 的定义已变更，删除后重建：{} -> {}", name, key.toJson(), keys.toJson());
                    collection.dropIndex(name);
                }
                break;
            }
        }
        collection.createIndex(keys, options);
    }
}
