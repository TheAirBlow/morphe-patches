/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1856
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.scrobbling;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.music.patches.scrobbling.lastfm.LastFM;
import app.morphe.extension.music.patches.scrobbling.listenbrainz.ListenBrainz;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;

/**
 * Persists scrobbles that could not be submitted (e.g. no network) and retries them later,
 * using each service's bulk endpoint so a large backlog doesn't take one request per track.
 */
public class ScrobbleQueue {
    private static final int MAX_QUEUE_SIZE = 200;

    private static final String SERVICE_LASTFM = "lastfm";
    private static final String SERVICE_LISTENBRAINZ = "listenbrainz";

    private ScrobbleQueue() {
    }

    /**
     * A single queued (or in-flight) scrobble. Plain data holder, filled in by the caller.
     */
    public static class Entry {
        public String service;
        public String artist;
        public String track;
        @Nullable public String album;
        public int duration;
        /** When the track was played (UNIX seconds), i.e. Last.fm/ListenBrainz's listen timestamp. */
        public long timestamp;
        @Nullable public String songId;
        /** When this entry was added to the queue (UNIX seconds), distinct from {@link #timestamp}. */
        public long queuedAt;
    }

    public static void enqueueLastFM(Entry entry) {
        entry.service = SERVICE_LASTFM;
        enqueue(entry);
    }

    public static void enqueueListenBrainz(Entry entry) {
        entry.service = SERVICE_LISTENBRAINZ;
        enqueue(entry);
    }

    private static synchronized void enqueue(Entry entry) {
        entry.queuedAt = System.currentTimeMillis() / 1000;
        try {
            JSONArray queue = readQueue();
            queue.put(entryToJson(entry));

            while (queue.length() > MAX_QUEUE_SIZE) {
                queue.remove(0);
            }

            Settings.SCROBBLING_QUEUE.save(queue.toString());
            Logger.printDebug(() -> entry.service + ": queued offline scrobble for " + entry.artist + " - " + entry.track);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to queue scrobble", ex);
        }
    }

    /**
     * Retries queued scrobbles in bulk, per service. Must be called from a background thread.
     */
    public static synchronized void flush() {
        List<Entry> entries = readEntries();
        if (entries.isEmpty()) return;

        List<Entry> remaining = new ArrayList<>();
        remaining.addAll(flushService(SERVICE_LASTFM, entries));
        remaining.addAll(flushService(SERVICE_LISTENBRAINZ, entries));

        if (remaining.size() != entries.size()) {
            saveEntries(remaining);
        }
    }

    private static List<Entry> flushService(String service, List<Entry> entries) {
        List<Entry> pending = new ArrayList<>();
        for (Entry entry : entries) {
            if (service.equals(entry.service)) pending.add(entry);
        }
        if (pending.isEmpty()) return pending;

        if (SERVICE_LASTFM.equals(service)) {
            if (!Settings.LASTFM_SCROBBLING.get()) return pending; // keep queued until re-enabled
            String sk = Settings.LASTFM_SESSION_KEY.get();
            if (sk.isBlank()) return List.of(); // logged out, drop them

            List<Entry> retry = new ArrayList<>();
            for (int i = 0; i < pending.size(); i += LastFM.MAX_BATCH_SIZE) {
                List<Entry> chunk = pending.subList(i, Math.min(i + LastFM.MAX_BATCH_SIZE, pending.size()));
                boolean[] shouldRetry = LastFM.scrobbleBatchSync(sk, chunk);
                if (shouldRetry == null) {
                    // Failed to reach the server at all, stop for this round.
                    retry.addAll(pending.subList(i, pending.size()));
                    break;
                }
                for (int j = 0; j < chunk.size(); j++) {
                    if (shouldRetry[j]) retry.add(chunk.get(j));
                }
            }
            return retry;
        }

        if (SERVICE_LISTENBRAINZ.equals(service)) {
            if (!Settings.LISTENBRAINZ_SCROBBLING.get()) return pending;
            String token = Settings.LISTENBRAINZ_USER_TOKEN.get();
            if (token.isBlank()) return List.of();

            List<Entry> retry = new ArrayList<>();
            for (int i = 0; i < pending.size(); i += ListenBrainz.MAX_BATCH_SIZE) {
                List<Entry> chunk = pending.subList(i, Math.min(i + ListenBrainz.MAX_BATCH_SIZE, pending.size()));
                if (!ListenBrainz.scrobbleBatchSync(token, chunk)) {
                    retry.addAll(pending.subList(i, pending.size()));
                    break;
                }
            }
            return retry;
        }

        return List.of(); // unknown service, drop
    }

    private static JSONObject entryToJson(Entry entry) throws Exception {
        JSONObject json = new JSONObject();
        json.put("service", entry.service);
        json.put("artist", entry.artist);
        json.put("track", entry.track);
        if (entry.album != null && !entry.album.isBlank()) json.put("album", entry.album);
        if (entry.duration > 0) json.put("duration", entry.duration);
        json.put("timestamp", entry.timestamp);
        if (entry.songId != null && !entry.songId.isBlank()) json.put("songId", entry.songId);
        json.put("queuedAt", entry.queuedAt);
        return json;
    }

    @Nullable
    private static Entry entryFromJson(JSONObject json) {
        String artist = json.optString("artist");
        String track = json.optString("track");
        if (artist.isBlank() || track.isBlank()) return null;

        Entry entry = new Entry();
        entry.service = json.optString("service");
        entry.artist = artist;
        entry.track = track;
        entry.album = json.optString("album", null);
        entry.duration = json.optInt("duration", 0);
        entry.timestamp = json.optLong("timestamp");
        entry.songId = json.optString("songId", null);
        // Entries queued before this field existed fall back to the listen time itself.
        entry.queuedAt = json.optLong("queuedAt", entry.timestamp);
        return entry;
    }

    private static List<Entry> readEntries() {
        JSONArray queue = readQueue();
        List<Entry> entries = new ArrayList<>(queue.length());
        for (int i = 0; i < queue.length(); i++) {
            JSONObject json = queue.optJSONObject(i);
            Entry entry = json == null ? null : entryFromJson(json);
            if (entry != null) entries.add(entry);
        }
        return entries;
    }

    private static void saveEntries(List<Entry> entries) {
        try {
            JSONArray queue = new JSONArray();
            for (Entry entry : entries) {
                queue.put(entryToJson(entry));
            }
            Settings.SCROBBLING_QUEUE.save(queue.toString());
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to save scrobble queue", ex);
        }
    }

    private static JSONArray readQueue() {
        try {
            return new JSONArray(Settings.SCROBBLING_QUEUE.get());
        } catch (Exception ex) {
            return new JSONArray();
        }
    }

    public static int size() {
        return readQueue().length();
    }

    public static void clear() {
        Settings.SCROBBLING_QUEUE.resetToDefault();
    }
}
