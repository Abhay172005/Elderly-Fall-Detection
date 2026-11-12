package com.smartelderly.service;

import com.smartelderly.config.AppProperties;
import com.smartelderly.dto.DashboardStats;
import com.smartelderly.model.Incident;
import com.smartelderly.model.IncidentStatus;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationExpression;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dashboard figures, computed with MongoDB aggregations over the real incidents collection. */
@Service
public class DashboardService {
    private final MongoTemplate mongo;
    private final IncidentService incidents;
    private final ZoneId zone;

    public DashboardService(MongoTemplate mongo, IncidentService incidents, AppProperties props) {
        this.mongo = mongo;
        this.incidents = incidents;
        this.zone = ZoneId.of(props.timezone());
    }

    public DashboardStats stats(int days) {
        int window = Math.min(Math.max(days, 7), 90);

        // Counts per status in one aggregation; legacy/unknown values are folded into PENDING like the API does.
        Map<String, Long> distribution = new LinkedHashMap<>();
        for (IncidentStatus s : IncidentStatus.values()) distribution.put(s.name(), 0L);
        Aggregation byStatus = Aggregation.newAggregation(Aggregation.group("status").count().as("count"));
        for (Document d : mongo.aggregate(byStatus, Incident.class, Document.class).getMappedResults()) {
            String key = IncidentStatus.parse(d.getString("_id")).name();
            distribution.merge(key, ((Number) d.get("count")).longValue(), Long::sum);
        }
        long total = distribution.values().stream().mapToLong(Long::longValue).sum();

        LocalDate today = LocalDate.now(zone);
        Instant startOfToday = today.atStartOfDay(zone).toInstant();
        long todayCount = mongo.count(Query.query(Criteria.where("timestamp").gte(startOfToday)), Incident.class);

        long awaiting = mongo.count(Query.query(Criteria.where("status").in("VERIFIED", "PENDING")
                .and("acknowledgedAt").is(null)), Incident.class);

        Double avgConfidence = null;
        Aggregation avg = Aggregation.newAggregation(Aggregation.group().avg("confidence").as("avg"));
        Document avgDoc = mongo.aggregate(avg, Incident.class, Document.class).getUniqueMappedResult();
        if (avgDoc != null && avgDoc.get("avg") != null) avgConfidence = Math.round(((Number) avgDoc.get("avg")).doubleValue() * 1000) / 1000.0;

        // Daily trend in the configured time zone, zero-filled.
        LocalDate first = today.minusDays(window - 1L);
        AggregationExpression day = ctx -> new Document("$dateToString",
                new Document("format", "%Y-%m-%d").append("date", "$timestamp").append("timezone", zone.getId()));
        Aggregation trendAgg = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("timestamp").gte(first.atStartOfDay(zone).toInstant())),
                Aggregation.project().and(day).as("day"),
                Aggregation.group("day").count().as("count"));
        Map<String, Long> perDay = new HashMap<>();
        for (Document d : mongo.aggregate(trendAgg, Incident.class, Document.class).getMappedResults()) {
            perDay.put(d.getString("_id"), ((Number) d.get("count")).longValue());
        }
        List<DashboardStats.TrendPoint> trend = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
            trend.add(new DashboardStats.TrendPoint(d, perDay.getOrDefault(d.toString(), 0L)));
        }

        var recent = mongo.find(new Query().with(Sort.by(Sort.Direction.DESC, "timestamp")).limit(6), Incident.class)
                .stream().map(incidents::toDto).toList();

        return new DashboardStats(total, todayCount, distribution.get("VERIFIED"), distribution.get("PENDING"),
                distribution.get("RESOLVED"), distribution.get("FALSE_ALARM"), awaiting, avgConfidence, distribution, trend,
                recent, Instant.now());
    }
}
