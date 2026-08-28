# Security Fixes Group 3: Share System (Findings #4, #5, C3)

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create `MetadataLocationStripper` utility, strip IPTC/XMP location data from share responses, remove storage_key exposure, add owner_id predicate.

**Dependencies:** None — fully independent.

**Tech Stack:** Java 17, Spring Boot 3.4.2, PostgreSQL 16 (Flyway), JUnit 5 + Testcontainers.

**Design Spec:** `docs/superpowers/specs/2026-03-13-security-findings-fixes-design.md` (Sections 2-3)

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `api/src/main/java/org/jphototagger/api/service/MetadataLocationStripper.java` | Create | Single source of truth for location key stripping |
| `api/src/main/java/org/jphototagger/api/service/ShareService.java` | Modify | Delegate to stripper, add IPTC/XMP methods |
| `api/src/main/java/org/jphototagger/api/controller/ShareController.java` | Modify | Strip IPTC/XMP, fix storage_key exposure |
| `api/src/main/java/org/jphototagger/api/repository/ShareLookupRepository.java` | Modify | Add `ownerId` param to `findPhotoById` |
| `api/src/main/java/org/jphototagger/api/dto/PhotoMetadataResponse.java` | Modify | Delegate to stripper, remove private methods/constants |
| `api/src/test/java/org/jphototagger/api/service/MetadataLocationStripperTest.java` | Create | Unit tests for stripper |

---

### Task 1: Create `MetadataLocationStripper` utility (Finding #4)

**Files:**
- Create: `api/src/main/java/org/jphototagger/api/service/MetadataLocationStripper.java`
- Test: `api/src/test/java/org/jphototagger/api/service/MetadataLocationStripperTest.java`

- [ ] **Step 1: Write failing tests for MetadataLocationStripper**

```java
package org.jphototagger.api.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataLocationStripperTest {

    @Test
    void filterGpsFromExif_removesGpsKeys() {
        Map<String, Object> exif = new HashMap<>(Map.of(
                "GPS:GPSLatitude", 40.0,
                "GPS:GPSLongitude", -74.0,
                "EXIF:GPSAltitude", 100.0,
                "Make", "Canon",
                "Model", "EOS R5"
        ));
        Map<String, Object> result = MetadataLocationStripper.filterGpsFromExif(exif);
        assertThat(result).containsOnlyKeys("Make", "Model");
    }

    @Test
    void filterGpsFromExif_nullInput_returnsNull() {
        assertThat(MetadataLocationStripper.filterGpsFromExif(null)).isNull();
    }

    @Test
    void filterLocationFromIptc_removesLocationKeys() {
        Map<String, Object> iptc = new HashMap<>(Map.of(
                "City", "New York",
                "Province-State", "NY",
                "Sub-location", "Manhattan",
                "IPTC:Keywords", "photo"
        ));
        Map<String, Object> result = MetadataLocationStripper.filterLocationFromIptc(iptc);
        assertThat(result).containsOnlyKeys("IPTC:Keywords");
    }

    @Test
    void filterLocationFromIptc_nullInput_returnsNull() {
        assertThat(MetadataLocationStripper.filterLocationFromIptc(null)).isNull();
    }

    @Test
    void filterLocationFromXmp_removesGpsAndLocationKeys() {
        Map<String, Object> xmp = new HashMap<>(Map.of(
                "exif:GPSLatitude", "40.0",
                "photoshop:City", "New York",
                "iptc4xmpcore:Location", "Manhattan",
                "dc:title", "Test Photo"
        ));
        Map<String, Object> result = MetadataLocationStripper.filterLocationFromXmp(xmp);
        assertThat(result).containsOnlyKeys("dc:title");
    }

    @Test
    void filterLocationFromXmp_nullInput_returnsNull() {
        assertThat(MetadataLocationStripper.filterLocationFromXmp(null)).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails (class doesn't exist)**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.service.MetadataLocationStripperTest" --no-daemon`
Expected: FAIL (compilation error)

- [ ] **Step 3: Implement MetadataLocationStripper**

```java
package org.jphototagger.api.service;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single source of truth for stripping GPS and location data from photo metadata.
 * Static utility — no Spring dependency, usable from records and services alike.
 */
public final class MetadataLocationStripper {

    private MetadataLocationStripper() {}

    public static final Set<String> IPTC_LOCATION_KEYS = Set.of(
            "iptc:sub-location", "iptc:city", "iptc:province-state",
            "iptc:country-primary location code", "iptc:country-primary location name",
            "sub-location", "city", "province-state",
            "country-primary location code", "country-primary location name"
    );

    public static final Set<String> XMP_LOCATION_KEYS = Set.of(
            "photoshop:city", "photoshop:state", "photoshop:country",
            "iptc4xmpcore:location", "xmp:location"
    );

    /** Removes GPS-related keys from EXIF data. Returns null for null input. */
    public static Map<String, Object> filterGpsFromExif(Map<String, Object> exif) {
        if (exif == null) return null;
        return exif.entrySet().stream()
                .filter(e -> !e.getKey().toLowerCase().contains("gps"))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Removes location-related keys from IPTC data. Returns null for null input. */
    public static Map<String, Object> filterLocationFromIptc(Map<String, Object> iptc) {
        if (iptc == null) return null;
        return iptc.entrySet().stream()
                .filter(e -> !IPTC_LOCATION_KEYS.contains(e.getKey().toLowerCase()))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Removes GPS and location-related keys from XMP data. Returns null for null input. */
    public static Map<String, Object> filterLocationFromXmp(Map<String, Object> xmp) {
        if (xmp == null) return null;
        return xmp.entrySet().stream()
                .filter(e -> {
                    String lower = e.getKey().toLowerCase();
                    return !lower.contains("gps") && !XMP_LOCATION_KEYS.contains(lower);
                })
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
```

- [ ] **Step 4: Run tests**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.service.MetadataLocationStripperTest" --no-daemon`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/service/MetadataLocationStripper.java api/src/test/java/org/jphototagger/api/service/MetadataLocationStripperTest.java
git commit -m "feat(share): create MetadataLocationStripper as single source of truth for location key filtering"
```

---

### Task 2: Migrate `PhotoMetadataResponse.withoutGps()` to use stripper

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/dto/PhotoMetadataResponse.java:39-83`
- Test: `api/src/test/java/org/jphototagger/api/dto/PhotoMetadataResponseTest.java` (existing)

- [ ] **Step 1: Run existing PhotoMetadataResponseTest to establish baseline**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.dto.PhotoMetadataResponseTest" --no-daemon`
Expected: PASS

- [ ] **Step 2: Update `withoutGps()` to delegate to MetadataLocationStripper**

In `PhotoMetadataResponse.java`:

1. Remove the two private constant sets (lines 39-49): `IPTC_LOCATION_KEYS`, `XMP_LOCATION_KEYS`
2. Remove the three private methods (lines 58-83): `filterGpsKeys`, `filterGpsAndLocationKeys`, `filterLocationKeys`
3. Add import: `import org.jphototagger.api.service.MetadataLocationStripper;`
4. Update `withoutGps()` (lines 51-56):

```java
public PhotoMetadataResponse withoutGps() {
    return new PhotoMetadataResponse(photoId, null, null,
            MetadataLocationStripper.filterGpsFromExif(exifData),
            MetadataLocationStripper.filterLocationFromIptc(iptcData),
            MetadataLocationStripper.filterLocationFromXmp(xmpData),
            extractedAt);
}
```

- [ ] **Step 3: Run existing tests to verify delegation works identically**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.dto.PhotoMetadataResponseTest" --no-daemon`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/dto/PhotoMetadataResponse.java
git commit -m "refactor(share): delegate PhotoMetadataResponse.withoutGps() to MetadataLocationStripper"
```

---

### Task 3: Add IPTC/XMP stripping to ShareService (Finding #4)

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/service/ShareService.java:47,148-166`
- Test: `api/src/test/java/org/jphototagger/api/service/ShareServiceTest.java` (existing)

- [ ] **Step 1: Write failing test for IPTC/XMP stripping in ShareService**

Add to `ShareServiceTest.java`:

```java
@Test
void stripLocationFromIptc_removesLocationKeys() {
    String iptcJson = "{\"City\":\"New York\",\"Province-State\":\"NY\",\"IPTC:Keywords\":\"photo\"}";
    String result = shareService.stripLocationFromIptc(iptcJson);
    assertThat(result).contains("IPTC:Keywords");
    assertThat(result).doesNotContain("City");
    assertThat(result).doesNotContain("Province-State");
}

@Test
void stripLocationFromXmp_removesGpsAndLocationKeys() {
    String xmpJson = "{\"exif:GPSLatitude\":\"40.0\",\"photoshop:City\":\"New York\",\"dc:title\":\"Test\"}";
    String result = shareService.stripLocationFromXmp(xmpJson);
    assertThat(result).contains("dc:title");
    assertThat(result).doesNotContain("GPSLatitude");
    assertThat(result).doesNotContain("photoshop:City");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.service.ShareServiceTest.stripLocationFromIptc_removesLocationKeys" --no-daemon`
Expected: FAIL (method doesn't exist)

- [ ] **Step 3: Update ShareService — delegate stripGpsFromExif to stripper, add IPTC/XMP methods**

In `ShareService.java`:

1. Add import: `import org.jphototagger.api.service.MetadataLocationStripper;`
2. Remove `GPS_KEY_PATTERN` constant (line 47)
3. Replace `stripGpsFromExif` method body (lines 148-166) to delegate to stripper:

```java
public String stripGpsFromExif(String exifJson) {
    if (exifJson == null) return null;
    try {
        Map<String, Object> exifMap = objectMapper.readValue(exifJson,
                new TypeReference<Map<String, Object>>() {});
        Map<String, Object> stripped = MetadataLocationStripper.filterGpsFromExif(exifMap);
        return objectMapper.writeValueAsString(stripped);
    } catch (JsonProcessingException e) {
        log.warn("Failed to parse EXIF JSON for GPS stripping, returning null to prevent GPS data leak", e);
        return null;
    }
}

public String stripLocationFromIptc(String iptcJson) {
    if (iptcJson == null) return null;
    try {
        Map<String, Object> iptcMap = objectMapper.readValue(iptcJson,
                new TypeReference<Map<String, Object>>() {});
        Map<String, Object> stripped = MetadataLocationStripper.filterLocationFromIptc(iptcMap);
        return objectMapper.writeValueAsString(stripped);
    } catch (JsonProcessingException e) {
        log.warn("Failed to parse IPTC JSON for location stripping, returning null", e);
        return null;
    }
}

public String stripLocationFromXmp(String xmpJson) {
    if (xmpJson == null) return null;
    try {
        Map<String, Object> xmpMap = objectMapper.readValue(xmpJson,
                new TypeReference<Map<String, Object>>() {});
        Map<String, Object> stripped = MetadataLocationStripper.filterLocationFromXmp(xmpMap);
        return objectMapper.writeValueAsString(stripped);
    } catch (JsonProcessingException e) {
        log.warn("Failed to parse XMP JSON for location stripping, returning null", e);
        return null;
    }
}
```

4. Remove `import java.util.regex.Pattern;` if no other usage.

- [ ] **Step 4: (ShareController change skipped — Task 4 Step 3 supersedes)**

> **Note:** Task 4 Step 3 replaces the entire photo block in `getShare()`, including the IPTC/XMP stripping lines. Do NOT modify `ShareController.java` in this task. Task 4 applies the combined controller change (IPTC/XMP stripping + storage_key removal + owner_id predicate) in one step.

- [ ] **Step 5: Run tests**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --tests "org.jphototagger.api.service.ShareServiceTest" --no-daemon`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/service/ShareService.java api/src/test/java/org/jphototagger/api/service/ShareServiceTest.java
git commit -m "fix(share): strip IPTC/XMP location data in share responses (Finding #4)"
```

---

### Task 4: Fix storage_key exposure + add owner_id predicate (Findings #5, C3)

**Files:**
- Modify: `api/src/main/java/org/jphototagger/api/controller/ShareController.java:95-142,148-165`
- Modify: `api/src/main/java/org/jphototagger/api/repository/ShareLookupRepository.java:64-74`

- [ ] **Step 1: Add `ownerId` parameter to `ShareLookupRepository.findPhotoById()`**

In `ShareLookupRepository.java`, replace `findPhotoById` (lines 64-74):

```java
public Optional<Map<String, Object>> findPhotoById(UUID photoId, UUID ownerId) {
    var results = jdbc.queryForList(
        "SELECT p.id, p.filename, p.caption, p.title, p.description, " +
        "       p.size_bytes, p.taken_at, p.uploaded_at, p.processing_status, p.storage_key, " +
        "       pm.exif_data, pm.iptc_data, pm.xmp_data " +
        "FROM photos p " +
        "LEFT JOIN photo_metadata pm ON pm.photo_id = p.id " +
        "WHERE p.id = ? AND p.user_id = ? AND p.deleted_at IS NULL",
        photoId, ownerId);
    return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
}
```

- [ ] **Step 2: Extract `enrichPhotoWithPresignedUrls()` helper in ShareController**

In `ShareController.java`, add private method:

```java
private void enrichPhotoWithPresignedUrls(Map<String, Object> photo, UUID ownerId) {
    UUID photoId = (UUID) photo.get("id");
    Object storageKey = photo.remove("storage_key");
    if (storageKey != null && photoId != null) {
        photo.put("thumbnailUrl", storageService.generateThumbnailPresignedUrl(
                storageService.thumbnailSmKey(ownerId, photoId)));
        photo.put("originalUrl", storageService.generateOriginalPresignedUrl(storageKey.toString()));
    }
}
```

- [ ] **Step 3: Update `getShare()` to use ownerId and enrichPhotoWithPresignedUrls**

Replace the photo handling block in `getShare()` (lines 95-132):

```java
if ("photo".equals(resourceType)) {
    UUID ownerId = (UUID) shareData.get("user_id");
    var photoOpt = shareLookupRepository.findPhotoById(resourceId, ownerId);
    if (photoOpt.isEmpty()) {
        throw new jakarta.persistence.EntityNotFoundException("Share not found");
    }
    Map<String, Object> photo = new HashMap<>(photoOpt.get());

    if (!includeGps) {
        if (photo.get("exif_data") != null)
            photo.put("exif_data", shareService.stripGpsFromExif(photo.get("exif_data").toString()));
        if (photo.get("iptc_data") != null)
            photo.put("iptc_data", shareService.stripLocationFromIptc(photo.get("iptc_data").toString()));
        if (photo.get("xmp_data") != null)
            photo.put("xmp_data", shareService.stripLocationFromXmp(photo.get("xmp_data").toString()));
    }

    enrichPhotoWithPresignedUrls(photo, ownerId);
    response.put("photo", photo);
```

- [ ] **Step 4: Update `getSharedAlbumPhotos()` to strip storage_key from album photos**

Replace the return statement in `getSharedAlbumPhotos()` (line 164):

```java
UUID albumOwnerId = (UUID) shareData.get("user_id");
return shareLookupRepository.findAlbumPhotos(albumId, capped).map(rawPhoto -> {
    Map<String, Object> photo = new HashMap<>(rawPhoto);
    enrichPhotoWithPresignedUrls(photo, albumOwnerId);
    return photo;
});
```

- [ ] **Step 5: Add tests for storage_key removal and owner_id predicate**

Add to `ShareControllerTest.java` (or `ShareServiceTest.java`):

```java
@Test
void getShare_responseDoesNotContainStorageKey() throws Exception {
    // Create a share, then retrieve it and verify storage_key is not in the response
    // (use an existing share setup from test fixtures)
    var result = mockMvc.perform(get("/shares/{token}", shareToken))
            .andExpect(status().isOk())
            .andReturn();
    String body = result.getResponse().getContentAsString();
    assertThat(body).doesNotContain("storage_key");
}

@Test
void getShare_photoWithWrongOwner_returns404() throws Exception {
    // Verify that findPhotoById with mismatched ownerId returns empty/404
    // This tests the owner_id predicate added to ShareLookupRepository
    // Create a share pointing to a photo, then tamper the owner_id
    // The share lookup should fail because the photo's user_id doesn't match
}
```

> **Note:** Adapt these test skeletons to the actual test infrastructure. The key assertions: (1) `storage_key` never appears in share API responses, (2) `findPhotoById(photoId, wrongOwnerId)` returns empty.

- [ ] **Step 6: Run full test suite**

Run: `cd /home/ubuntu/jpt_saas && ./gradlew :api:test --no-daemon`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add api/src/main/java/org/jphototagger/api/controller/ShareController.java api/src/main/java/org/jphototagger/api/repository/ShareLookupRepository.java api/src/test/java/org/jphototagger/api/controller/ShareControllerTest.java
git commit -m "fix(share): remove storage_key from responses, add owner_id predicate (Findings #5, C3)"
```
