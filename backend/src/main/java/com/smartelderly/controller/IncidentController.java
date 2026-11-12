package com.smartelderly.controller;

import com.smartelderly.dto.IncidentDto;
import com.smartelderly.dto.IncidentRequests.AcknowledgeRequest;
import com.smartelderly.dto.IncidentRequests.DetectionReport;
import com.smartelderly.dto.IncidentRequests.IncidentUpdate;
import com.smartelderly.dto.IncidentRequests.ManualIncident;
import com.smartelderly.dto.IncidentRequests.ResolveRequest;
import com.smartelderly.dto.PageResponse;
import com.smartelderly.service.IncidentService;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api")
public class IncidentController {
    private final IncidentService service;

    public IncidentController(IncidentService service) {
        this.service = service;
    }

    @GetMapping("/incidents")
    public PageResponse<IncidentDto> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "timestamp") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return service.search(q, status, from, to, sort, direction, page, size);
    }

    @GetMapping(value = "/incidents/export", produces = "text/csv")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        byte[] body = ("﻿" + service.exportCsv(q, status, from, to)).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("incidents-" + LocalDate.now() + ".csv").build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    @GetMapping("/incidents/{id}")
    public IncidentDto one(@PathVariable String id) {
        return service.get(id);
    }

    /** Manually reported incident (admin only). */
    @PostMapping("/incidents")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<IncidentDto> create(@Valid @RequestBody ManualIncident body, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createManual(body, CurrentUser.name(jwt)));
    }

    /** Callback from the ML service after temporal verification (X-Service-Key). Idempotent on detectionId. */
    @PostMapping("/detection/fall")
    public ResponseEntity<IncidentDto> detected(@Valid @RequestBody DetectionReport body) {
        IncidentService.CreateResult result = service.recordDetection(body);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.incident());
    }

    @PutMapping("/incidents/{id}")
    public IncidentDto update(@PathVariable String id, @Valid @RequestBody IncidentUpdate body, @AuthenticationPrincipal Jwt jwt) {
        return service.update(id, body, CurrentUser.name(jwt));
    }

    @PutMapping("/incidents/{id}/acknowledge")
    public IncidentDto acknowledge(@PathVariable String id, @Valid @RequestBody(required = false) AcknowledgeRequest body,
                                   @AuthenticationPrincipal Jwt jwt) {
        return service.acknowledge(id, body, CurrentUser.name(jwt));
    }

    @PutMapping("/incidents/{id}/resolve")
    public IncidentDto resolve(@PathVariable String id, @Valid @RequestBody(required = false) ResolveRequest body,
                               @AuthenticationPrincipal Jwt jwt) {
        return service.resolve(id, body, CurrentUser.name(jwt));
    }

    @DeleteMapping("/incidents/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/incidents/{id}/image")
    public ResponseEntity<FileSystemResource> image(@PathVariable String id) {
        Path p = service.imageFile(id);
        MediaType type = p.getFileName().toString().toLowerCase().endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore()).body(new FileSystemResource(p));
    }

    @GetMapping("/incidents/{id}/report")
    public ResponseEntity<FileSystemResource> report(@PathVariable String id) {
        Path p = service.reportFile(id);
        String name = "fall-report-" + service.get(id).incidentId() + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noStore())
                .body(new FileSystemResource(p));
    }

    @PostMapping("/incidents/{id}/report")
    public IncidentDto regenerateReport(@PathVariable String id) {
        return service.regenerateReport(id);
    }

    @PostMapping("/incidents/{id}/notify")
    public IncidentDto resendEmail(@PathVariable String id) {
        return service.resendEmail(id);
    }
}
