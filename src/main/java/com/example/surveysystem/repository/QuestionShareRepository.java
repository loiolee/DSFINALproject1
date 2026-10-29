package com.example.surveysystem.repository;

import com.example.surveysystem.domain.QuestionShare;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/** 题目共享授权。 */
public interface QuestionShareRepository extends MongoRepository<QuestionShare, String> {

    /** 别人共享给我的题目，最近授权的在前。 */
    List<QuestionShare> findByGranteeIdOrderByCreatedAtDesc(String granteeId);

    /** 我共享出去的授权记录。 */
    List<QuestionShare> findByQuestionIdOrderByCreatedAtDesc(String questionId);

    Optional<QuestionShare> findByQuestionIdAndGranteeId(String questionId, String granteeId);

    boolean existsByQuestionIdAndGranteeId(String questionId, String granteeId);

    void deleteByQuestionIdAndGranteeId(String questionId, String granteeId);

    /** 该题还剩几条授权。撤销后据此把 {@code visibility} 回退为 private。 */
    long countByQuestionId(String questionId);
}
