package com.example.surveysystem.config;

import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
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
    private final MongoTemplate mongoTemplate;

    public MongoIndexConfig(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 同一用户名只能注册一次。
        mongoTemplate.getCollection("users").createIndex(
                new Document("username", 1),
                new IndexOptions().unique(true).name("uniq_username"));

        // 仅对字符串 publicId 建唯一索引，草稿中缺省/null 的 publicId 不互相冲突。
        mongoTemplate.getCollection("surveys").createIndex(
                new Document("publicId", 1),
                new IndexOptions().unique(true).name("uniq_survey_public_id")
                        .partialFilterExpression(new Document("publicId", new Document("$type", "string"))));
        // 支撑「我的问卷」按创建者倒序列表。
        mongoTemplate.getCollection("surveys").createIndex(
                new Document("ownerId", 1).append("createdAt", -1),
                new IndexOptions().name("idx_owner_created_at"));

        // allowMultiple=false 时 submissionKey 有值；匿名和非匿名的键均由该索引防止并发重复创建。
        mongoTemplate.getCollection("responses").createIndex(
                new Document("surveyId", 1).append("submissionKey", 1),
                new IndexOptions().unique(true).name("uniq_survey_submission_key")
                        .partialFilterExpression(new Document("submissionKey", new Document("$type", "string"))));
        // 支撑统计「按 surveyId + status 过滤已提交答卷」。
        mongoTemplate.getCollection("responses").createIndex(
                new Document("surveyId", 1).append("status", 1),
                new IndexOptions().name("idx_survey_status"));
        // 多键索引：answers 是数组，统计管道先 $unwind 再按 answers.questionId 过滤，
        // 该索引让「某问卷某道题」的聚合只扫描相关数组元素，避免全集合扫描。
        mongoTemplate.getCollection("responses").createIndex(
                new Document("surveyId", 1).append("answers.questionId", 1),
                new IndexOptions().name("idx_survey_answer_question"));
    }
}
