package com.smartelderly.config;

import com.smartelderly.model.Incident;
import com.smartelderly.model.User;
import com.smartelderly.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/**
 * Creates MongoDB indexes and the optional bootstrap admin. Runs on a background thread so the API still starts
 * (and reports the database as DOWN on /api/health) when MongoDB is temporarily unreachable.
 */
@Component
public class StartupInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StartupInitializer.class);

    private final MongoTemplate mongo;
    private final AuthService auth;

    public StartupInitializer(MongoTemplate mongo, AuthService auth) {
        this.mongo = mongo;
        this.auth = auth;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread t = new Thread(this::initialize, "mongo-init");
        t.setDaemon(true);
        t.start();
    }

    private void initialize() {
        // Atlas may be unreachable at boot (network, IP access list); keep retrying so indexes always get created.
        for (int attempt = 1; ; attempt++) {
            try {
                mongo.getDb().runCommand(new org.bson.Document("ping", 1));
            } catch (Exception e) {
                if (attempt == 1) log.warn("MongoDB not reachable yet ({}); retrying index setup in the background.", e.getClass().getSimpleName());
                sleep(attempt <= 20 ? 30_000 : 120_000);
                continue;
            }
            IndexOperations incidents = mongo.indexOps(Incident.class);
            boolean ok = ensure(incidents, new Index().on("incidentId", Sort.Direction.ASC).unique().named("uk_incidentId"))
                    & ensure(incidents, new Index().on("detectionId", Sort.Direction.ASC).unique()
                    .partial(PartialIndexFilter.of(Criteria.where("detectionId").type(2))).named("uk_detectionId"))
                    & ensure(incidents, new Index().on("timestamp", Sort.Direction.DESC).named("ix_timestamp"))
                    & ensure(incidents, new Index().on("status", Sort.Direction.ASC).on("timestamp", Sort.Direction.DESC).named("ix_status_timestamp"))
                    & ensure(incidents, new Index().on("elderlyName", Sort.Direction.ASC).named("ix_elderlyName"))
                    & ensure(mongo.indexOps(User.class), new Index().on("email", Sort.Direction.ASC).unique().named("uk_email"));
            try {
                auth.bootstrapAdmin();
            } catch (Exception e) {
                log.warn("Bootstrap admin check skipped: {}", e.getClass().getSimpleName());
            }
            log.info(ok ? "MongoDB indexes verified." : "MongoDB index setup finished with warnings (see above).");
            return;
        }
    }

    private boolean ensure(IndexOperations ops, Index index) {
        try {
            ops.ensureIndex(index);
            return true;
        } catch (Exception e) {
            String name = String.valueOf(index.getIndexOptions().get("name"));
            log.warn("Could not create index {}: {}. Existing duplicate values may need cleaning up.", name, e.getClass().getSimpleName());
            return false;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
