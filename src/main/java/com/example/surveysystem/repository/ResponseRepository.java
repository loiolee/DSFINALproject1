package com.example.surveysystem.repository;

import com.example.surveysystem.domain.Response;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface ResponseRepository extends MongoRepository<Response, String> {
    Optional<Response> findBySurveyIdAndSubmissionKey(String surveyId, String submissionKey);
    long countBySurveyIdAndStatus(String surveyId, String status);
}
