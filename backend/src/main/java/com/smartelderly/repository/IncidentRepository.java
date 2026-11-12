package com.smartelderly.repository;

import com.smartelderly.model.Incident;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface IncidentRepository extends MongoRepository<Incident, String> {
    Optional<Incident> findByDetectionId(String detectionId);

    boolean existsByIncidentId(String incidentId);
}
