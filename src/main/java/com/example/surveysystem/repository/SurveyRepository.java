package com.example.surveysystem.repository;

import com.example.surveysystem.domain.Survey;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SurveyRepository extends MongoRepository<Survey, String> {
    List<Survey> findByOwnerIdOrderByCreatedAtDesc(String ownerId);
    Optional<Survey> findByPublicId(String publicId);

    /**
     * 有多少份问卷引用了这道题库题目（需求「查看某个题被哪些问卷使用」的计数）。
     *
     * <p>用显式 {@code @Query} 而不是派生方法名：{@code questions.questionId} 是内嵌数组里的字段，
     * 派生方法名在多层嵌套属性上容易解析歧义，写成查询语句更明确。</p>
     */
    @Query(value = "{ 'questions.questionId': ?0 }", count = true)
    long countByLibraryQuestionId(String questionId);

    /**
     * 「这道题被哪些问卷使用」的反向查询，依赖多键索引 {@code idx_survey_question_ref}。
     *
     * <p>返回完整文档而不是投影：调用方需要问卷标题、状态、题目实例与绑定版本号，
     * 一次性取回比拼多次投影简单，而且命中索引后只扫相关文档，不会全集合扫描。</p>
     */
    @Query("{ 'questions.questionId': ?0 }")
    List<Survey> findByLibraryQuestionId(String questionId);
}
