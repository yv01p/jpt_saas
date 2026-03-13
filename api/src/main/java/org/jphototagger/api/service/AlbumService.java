package org.jphototagger.api.service;

import jakarta.persistence.EntityNotFoundException;
import org.jphototagger.api.entity.Album;
import org.jphototagger.api.entity.AlbumPhoto;
import org.jphototagger.api.entity.AlbumPhotoId;
import org.jphototagger.api.entity.Photo;
import org.jphototagger.api.repository.AlbumPhotoRepository;
import org.jphototagger.api.repository.AlbumRepository;
import org.jphototagger.api.repository.PhotoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AlbumService {

    private final AlbumRepository albumRepository;
    private final AlbumPhotoRepository albumPhotoRepository;
    private final PhotoRepository photoRepository;

    public AlbumService(AlbumRepository albumRepository,
                        AlbumPhotoRepository albumPhotoRepository,
                        PhotoRepository photoRepository) {
        this.albumRepository = albumRepository;
        this.albumPhotoRepository = albumPhotoRepository;
        this.photoRepository = photoRepository;
    }

    @Transactional(readOnly = true)
    public Page<Album> listAlbums(UUID userId, int page, int size) {
        return albumRepository.findByUserIdOrderByNameAsc(
                userId, PageRequest.of(page, Math.min(size, 100)));
    }

    @Transactional(readOnly = true)
    public Album getAlbum(UUID userId, UUID albumId) {
        return albumRepository.findById(albumId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new EntityNotFoundException("Album not found"));
    }

    @Transactional
    public Album createAlbum(UUID userId, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }
        Album album = new Album();
        album.setUserId(userId);
        album.setName(name);
        return albumRepository.save(album);
    }

    @Transactional
    public Album updateAlbum(UUID userId, UUID albumId, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }
        Album album = getAlbum(userId, albumId);
        album.setName(name);
        return albumRepository.save(album);
    }

    @Transactional
    public void deleteAlbum(UUID userId, UUID albumId) {
        Album album = getAlbum(userId, albumId);
        albumRepository.delete(album);
    }

    /**
     * Add a photo to an album. Verifies both the album and the photo belong to the user
     * before inserting, so cross-tenant attempts get a 404 rather than a DB constraint error.
     */
    @Transactional
    public void addPhoto(UUID userId, UUID albumId, UUID photoId) {
        getAlbum(userId, albumId);
        photoRepository.findById(photoId)
                .filter(p -> p.getUserId().equals(userId) && p.getDeletedAt() == null)
                .orElseThrow(() -> new EntityNotFoundException("Photo not found"));

        AlbumPhoto ap = new AlbumPhoto();
        ap.setAlbumId(albumId);
        ap.setPhotoId(photoId);
        ap.setUserId(userId);
        albumPhotoRepository.save(ap);
    }

    @Transactional
    public void removePhoto(UUID userId, UUID albumId, UUID photoId) {
        getAlbum(userId, albumId);
        albumPhotoRepository.deleteByAlbumIdAndPhotoIdAndUserId(albumId, photoId, userId);
    }

    @Transactional(readOnly = true)
    public List<Photo> getAlbumPhotos(UUID userId, UUID albumId) {
        getAlbum(userId, albumId);
        List<AlbumPhoto> links = albumPhotoRepository.findByAlbumIdAndUserId(albumId, userId);
        if (links.isEmpty()) return List.of();
        List<UUID> photoIds = links.stream().map(AlbumPhoto::getPhotoId).toList();
        return photoRepository.findAllById(photoIds).stream()
                .filter(p -> p.getDeletedAt() == null)
                .toList();
    }
}
