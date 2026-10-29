package com.example.surveysystem.repository;

import com.example.surveysystem.domain.QuestionVersion;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/** 题目版本。版本记录只增不改，因此这里没有更新相关的方法。 */
public interface QuestionVersionRepository extends MongoRepository<QuestionVersion, String> {

    /** 版本历史，最新的在前。 */
    List<QuestionVersion> findByQuestionIdOrderByVersionNoDesc(String questionId);

    Optional<QuestionVersion> findByQuestionIdAndVersionNo(String questionId, Integer versionNo);

    /** 取当前最大版本号，用于生成下一个版本号。 */
    Optional<QuestionVersion> findFirstByQuestionIdOrderByVersionNoDesc(String questionId);
}
