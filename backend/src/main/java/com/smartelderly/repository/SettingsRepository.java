package com.smartelderly.repository;

import com.smartelderly.model.AppSettings;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SettingsRepository extends MongoRepository<AppSettings, String> {
}
