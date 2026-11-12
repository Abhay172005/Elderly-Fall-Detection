package com.smartelderly.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartelderly.config.AppProperties;
import com.smartelderly.exception.ApiException;
import com.smartelderly.model.AppSettings;
import com.smartelderly.security.ServiceKeyFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * HTTP client for the Python ML service. The backend forwards uploaded videos together with the saved
 * detection settings, so the browser never talks to the ML service directly.
 */
@Service
public class MlServiceClient {
    private static final Logger log = LoggerFactory.getLogger(MlServiceClient.class);
    private static final Pattern JOB_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final RestClient client;
    private final RestClient quickClient;
    private final String serviceKey;

    public MlServiceClient(AppProperties props) {
        String base = props.ml().url();
        this.serviceKey = props.ml().serviceKey() == null ? "" : props.ml().serviceKey();
        SimpleClientHttpRequestFactory slow = new SimpleClientHttpRequestFactory();
        slow.setConnectTimeout(5000);
        slow.setReadTimeout(Math.max(30, props.ml().timeoutSeconds()) * 1000);
        SimpleClientHttpRequestFactory fast = new SimpleClientHttpRequestFactory();
        fast.setConnectTimeout(2000);
        fast.setReadTimeout(5000);
        this.client = RestClient.builder().baseUrl(base).requestFactory(slow).defaultHeader(ServiceKeyFilter.HEADER, serviceKey).build();
        this.quickClient = RestClient.builder().baseUrl(base).requestFactory(fast).defaultHeader(ServiceKeyFilter.HEADER, serviceKey).build();
    }

    public JsonNode health() {
        return quickClient.get().uri("/health").retrieve().body(JsonNode.class);
    }

    /** Starts an asynchronous detection job on the ML service. */
    public JsonNode startJob(Path video, String originalName, AppSettings s) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        // Send the upload under the user's original file name rather than the temp file's name.
        form.add("video", new FileSystemResource(video) {
            @Override
            public String getFilename() {
                return originalName;
            }
        });
        form.add("confidence_threshold", String.valueOf(s.getConfidenceThreshold()));
        form.add("verification_seconds", String.valueOf(s.getVerificationSeconds()));
        form.add("record_inconclusive", String.valueOf(s.isRecordInconclusive()));
        form.add("resident_name", s.getResidentName() == null ? "" : s.getResidentName());
        if (s.getResidentAge() != null) form.add("resident_age", String.valueOf(s.getResidentAge()));
        form.add("camera_name", s.getCameraName() == null ? "" : s.getCameraName());
        form.add("report_incident", "true");
        return call(() -> client.post().uri("/detect/video/jobs").contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form).retrieve().body(JsonNode.class));
    }

    public JsonNode job(String jobId) {
        checkId(jobId);
        return call(() -> quickClient.get().uri("/detect/video/jobs/{id}", jobId).retrieve().body(JsonNode.class));
    }

    public JsonNode cancel(String jobId) {
        checkId(jobId);
        return call(() -> quickClient.delete().uri("/detect/video/jobs/{id}", jobId).retrieve().body(JsonNode.class));
    }

    public JsonNode retryReport(String jobId) {
        checkId(jobId);
        return call(() -> client.post().uri("/detect/video/jobs/{id}/report", jobId).retrieve().body(JsonNode.class));
    }

    public ResponseEntity<byte[]> frame(String jobId) {
        checkId(jobId);
        return call(() -> quickClient.get().uri("/detect/video/jobs/{id}/frame", jobId).retrieve().toEntity(byte[].class));
    }

    private static void checkId(String id) {
        if (id == null || !JOB_ID.matcher(id).matches()) throw ApiException.badRequest("Invalid job id");
    }

    private <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            HttpStatusCode code = e.getStatusCode();
            String detail = extractDetail(e);
            if (code.value() == 404) throw ApiException.notFound(detail != null ? detail : "Detection job not found.");
            if (code.value() == 401 || code.value() == 403) {
                log.error("ML service rejected the service key. Check ML_SERVICE_API_KEY in both services.");
                throw ApiException.unavailable("The ML service rejected the backend's credentials.");
            }
            if (code.is4xxClientError()) throw new ApiException(HttpStatus.valueOf(code.value()), detail != null ? detail : "The ML service rejected the request.");
            throw ApiException.unavailable(detail != null ? detail : "The ML service failed to process the request.");
        } catch (ResourceAccessException e) {
            throw ApiException.unavailable("The ML service is not reachable. Start it with 'py main.py' in ml-service/.");
        }
    }

    private static String extractDetail(RestClientResponseException e) {
        try {
            JsonNode body = e.getResponseBodyAs(JsonNode.class);
            if (body != null && body.hasNonNull("detail")) {
                JsonNode d = body.get("detail");
                return d.isTextual() ? d.asText() : d.toString();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }
}
