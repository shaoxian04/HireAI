package com.hireai.application.port.storage;

/**
 * Outbound port for storing public agent media. Backend-only: the Supabase service key never
 * leaves the server. Implementations must return a publicly readable URL.
 */
public interface MediaStoragePort {

    /** Uploads (upserting) and returns the public URL. */
    String upload(String objectKey, String contentType, byte[] bytes);

    /** Best-effort delete of a previously returned public URL. Never throws on missing object. */
    void deleteByUrl(String publicUrl);
}
