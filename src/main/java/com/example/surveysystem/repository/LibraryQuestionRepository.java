package com.example.surveysystem.repository;

import com.example.surveysystem.domain.LibraryQuestion;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/** 题库题目身份。题目内容不在这里，在 {@link QuestionVersionRepository}。 */
public interface LibraryQuestionRepository extends MongoRepository<LibraryQuestion, String> {

    List<LibraryQuestion> findByOwnerIdOrderByUpdatedAtDesc(String ownerId);

    List<LibraryQuestion> findByOwnerIdAndStatusOrderByUpdatedAtDesc(String ownerId, String status);

    long countByOwnerIdAndStatus(String ownerId, String status);

    /**
     * 「从问卷加入题库」的幂等查询：同一份问卷里的同一道题只应存在一条题目身份。
     * 并发重复提交时由唯一索引 {@code uniq_question_source} 兜底。
     */
    Optional<LibraryQuestion> findByOwnerIdAndSourceSurveyIdAndSourceQuestionInstanceId(
            String ownerId, String sourceSurveyId, String sourceQuestionInstanceId);
}
