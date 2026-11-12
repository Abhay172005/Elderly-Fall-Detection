package com.smartelderly.service;

import com.smartelderly.config.AppProperties;
import com.smartelderly.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * Owns the on-disk layout {@code <storage>/incidents/<incidentId>/{frame.jpg,report.pdf}}.
 * Paths stored in MongoDB are relative to the storage root; absolute/legacy paths from the prototype are still readable.
 */
@Service
public class StorageService {
    private static final Logger log = LoggerFactory.getLogger(StorageService.class);
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final Path root;

    public StorageService(AppProperties props) {
        this.root = Paths.get(props.storageDir() == null || props.storageDir().isBlank() ? "storage" : props.storageDir())
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(root.resolve("incidents"));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create storage directory " + root, e);
        }
    }

    public Path incidentDir(String incidentId) {
        if (incidentId == null || !SAFE_ID.matcher(incidentId).matches()) {
            throw ApiException.badRequest("Invalid incident id");
        }
        Path dir = root.resolve("incidents").resolve(incidentId).normalize();
        if (!dir.startsWith(root)) throw ApiException.badRequest("Invalid incident id");
        return dir;
    }

    /** Validates the bytes as a JPEG or PNG image and stores them as the incident frame. Returns the stored reference. */
    public String saveFrame(String incidentId, byte[] image) throws IOException {
        String ext = imageExtension(image);
        if (ext == null) throw ApiException.badRequest("Frame image must be a JPEG or PNG");
        Path dir = incidentDir(incidentId);
        Files.createDirectories(dir);
        Path file = dir.resolve("frame." + ext);
        Files.write(file, image);
        return toReference(file);
    }

    public Path reportPath(String incidentId) {
        return incidentDir(incidentId).resolve("report.pdf");
    }

    public String toReference(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        return abs.startsWith(root) ? root.relativize(abs).toString().replace('\\', '/') : abs.toString().replace('\\', '/');
    }

    /** Resolves a stored reference to an existing regular file, or null. */
    public Path resolveExisting(String reference) {
        if (reference == null || reference.isBlank()) return null;
        try {
            Path p = Paths.get(reference);
            Path candidate;
            if (p.isAbsolute()) {
                candidate = p.normalize(); // legacy prototype records stored absolute paths
            } else if (reference.startsWith("storage/") || reference.startsWith("storage\\")) {
                candidate = root.getParent() == null ? p : root.getParent().resolve(p).normalize();
                if (!Files.isRegularFile(candidate)) candidate = Paths.get("").toAbsolutePath().resolve(p).normalize();
            } else {
                candidate = root.resolve(p).normalize();
                if (!candidate.startsWith(root)) return null;
            }
            return Files.isRegularFile(candidate) ? candidate : null;
        } catch (Exception e) {
            return null;
        }
    }

    public void deleteIncidentFiles(String incidentId) {
        try {
            FileSystemUtils.deleteRecursively(incidentDir(incidentId));
        } catch (Exception e) {
            log.warn("Could not delete files for incident {}: {}", incidentId, e.getMessage());
        }
    }

    public static String imageExtension(byte[] b) {
        if (b == null || b.length < 8) return null;
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "jpg";
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "png";
        return null;
    }
}
