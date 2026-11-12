package com.smartelderly.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartelderly.exception.ApiException;
import com.smartelderly.service.MlServiceClient;
import com.smartelderly.service.SettingsService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Video analysis jobs. The uploaded video is validated here and forwarded with the saved detection settings to the
 * ML service, which processes it asynchronously and reports confirmed incidents back to /api/detection/fall.
 */
@RestController
@RequestMapping("/api/detection/jobs")
public class DetectionJobController {
    private static final Set<String> EXTENSIONS = Set.of("mp4", "webm", "mov", "avi", "mkv");

    private final MlServiceClient ml;
    private final SettingsService settings;

    public DetectionJobController(MlServiceClient ml, SettingsService settings) {
        this.ml = ml;
        this.settings = settings;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<JsonNode> start(@RequestParam("video") MultipartFile video) throws IOException {
        if (video == null || video.isEmpty()) throw ApiException.badRequest("Please choose a video file.");
        String original = video.getOriginalFilename() == null ? "video.mp4" : Path.of(video.getOriginalFilename()).getFileName().toString();
        int dot = original.lastIndexOf('.');
        String ext = dot < 0 ? "" : original.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!EXTENSIONS.contains(ext)) throw ApiException.badRequest("Unsupported format. Use MP4, WEBM, MOV, AVI or MKV.");
        String safeName = original.replaceAll("[^A-Za-z0-9._ -]", "_");
        if (safeName.length() > 120) safeName = safeName.substring(safeName.length() - 120);

        Path tmp = Files.createTempFile("upload-", "." + ext);
        try {
            video.transferTo(tmp);
            JsonNode job = ml.startJob(tmp, safeName, settings.current());
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(job);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @GetMapping("/{jobId}")
    public JsonNode status(@PathVariable String jobId) {
        return ml.job(jobId);
    }

    @DeleteMapping("/{jobId}")
    public JsonNode cancel(@PathVariable String jobId) {
        return ml.cancel(jobId);
    }

    /** Re-sends the detection to the incident API, e.g. after the backend or database was temporarily unavailable. */
    @PostMapping("/{jobId}/report")
    public JsonNode retryReport(@PathVariable String jobId) {
        return ml.retryReport(jobId);
    }

    @GetMapping("/{jobId}/frame")
    public ResponseEntity<byte[]> frame(@PathVariable String jobId) {
        ResponseEntity<byte[]> upstream = ml.frame(jobId);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore()).body(upstream.getBody());
    }
}
