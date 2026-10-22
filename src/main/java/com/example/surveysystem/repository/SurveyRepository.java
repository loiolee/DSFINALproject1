package com.example.surveysystem.repository;

import com.example.surveysystem.domain.Survey;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface SurveyRepository extends MongoRepository<Survey, String> {
    List<Survey> findByOwnerIdOrderByCreatedAtDesc(String ownerId);
    Optional<Survey> findByPublicId(String publicId);
}
