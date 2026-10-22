/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1856
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.scrobbling.listenbrainz;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;

import app.morphe.extension.music.patches.scrobbling.ScrobbleManager;
import app.morphe.extension.music.patches.scrobbling.ScrobbleQueue;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

public class ListenBrainz {
    private static final String USER_AGENT = "Morphe/" + Utils.getPatchesReleaseVersion() + " (YTMusic/" + Utils.getAppVersionName() + ")";
    private static final String CLIENT_VERSION = Utils.getPatchesReleaseVersion();
    
    public static class TokenValidation {
        public boolean valid;
        public String userName;
        public String message;
    }

    /**
     * Synchronously validates the provided user token.
     * Must be called from a background thread.
     */
    public static TokenValidation validateToken(String token) throws Exception {
        Utils.verifyOffMainThread();
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("User token is missing or blank");
        }
        HttpURLConnection conn = Requester.openConnection(Settings.LISTENBRAINZ_BASE_URL.get() + "1/validate-token");
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Authorization", "Token " + token);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        final int code = conn.getResponseCode();
        if (code == 200) {
            String response = Requester.parseString(conn);
            JSONObject root = new JSONObject(response);
            TokenValidation validation = new TokenValidation();
            validation.valid = root.optBoolean("valid");
            validation.userName = root.optString("user_name");
            validation.message = root.optString("message");
            return validation;
        }

        TokenValidation validation = new TokenValidation();
        validation.valid = false;
        validation.message = "HTTP error " + code;
        return validation;
    }

    /**
     * Maximum number of listens ListenBrainz accepts in a single submit-listens request
     * (import-type payload array).
     */
    public static final int MAX_BATCH_SIZE = 1000;

    /**
     * Submits a scrobble asynchronously on a background thread.
     */
    public static void scrobbleAsync(String artist, String track, long timestamp,
                                     String songId, String album, int duration) {
        String token = Settings.LISTENBRAINZ_USER_TOKEN.get();
        if (token.isBlank()) {
            Logger.printDebug(() -> "Cannot scrobble, token not set or invalid");
            return;
        }
        ScrobbleManager.getInstance().runOnBackgroundThread(() -> {
            ScrobbleQueue.Entry entry = new ScrobbleQueue.Entry();
            entry.artist = artist;
            entry.track = track;
            entry.album = album;
            entry.duration = duration;
            entry.timestamp = timestamp;
            entry.songId = songId;

            if (!scrobbleBatchSync(token, List.of(entry))) {
                ScrobbleQueue.enqueueListenBrainz(entry);
            }
        });
    }

    /**
     * Submits up to {@link #MAX_BATCH_SIZE} listens in a single request, using the bulk
     * "import" listen type when there's more than one. ListenBrainz doesn't report per-listen
     * acceptance, so the whole batch is treated as a single outcome.
     *
     * <p>Must be called from a background thread.
     *
     * @return true if the batch was submitted or rejected by the server (nothing more to do),
     * false if it should be retried later, e.g. it failed to even reach the server.
     */
    public static boolean scrobbleBatchSync(String token, List<ScrobbleQueue.Entry> entries) {
        try {
            JSONArray payloadArray = new JSONArray();
            for (ScrobbleQueue.Entry entry : entries) {
                String effectiveAlbum = ScrobbleManager.getEffectiveAlbum(entry.artist, entry.track, entry.album);
                JSONObject payload = new JSONObject();
                payload.put("listened_at", entry.timestamp);
                payload.put("track_metadata", createTrackMetadata(entry.artist, entry.track, entry.songId, effectiveAlbum, entry.duration));
                payloadArray.put(payload);
            }

            JSONObject req = new JSONObject();
            req.put("listen_type", entries.size() > 1 ? "import" : "single");
            req.put("payload", payloadArray);

            if (postRequest("1/submit-listens", token, req.toString())) {
                Logger.printDebug(() -> "Successfully scrobbled " + entries.size() + " listen(s)");
            }
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "ListenBrainz scrobble failure", ex);
            return false;
        }
    }

    /**
     * Updates the Now Playing status asynchronously on a background thread.
     */
    public static void updateNowPlayingAsync(String artist, String track,
                                             String songId, String album, int duration) {
        String token = Settings.LISTENBRAINZ_USER_TOKEN.get();
        if (token.isBlank()) {
            Logger.printDebug(() -> "Cannot update Now Playing, token not set or invalid");
            return;
        }
        ScrobbleManager.getInstance().runOnBackgroundThread(() -> {
            try {
                String effectiveAlbum = ScrobbleManager.getEffectiveAlbum(artist, track, album);
                JSONObject req = new JSONObject();
                req.put("listen_type", "playing_now");

                JSONObject payload = new JSONObject();
                payload.put("track_metadata", createTrackMetadata(artist, track, songId, effectiveAlbum, duration));

                JSONArray payloadArray = new JSONArray();
                payloadArray.put(payload);
                req.put("payload", payloadArray);

                String jsonBody = req.toString();
                postRequest("1/submit-listens?return_msid=true", token, jsonBody);
                Logger.printDebug(() -> "Updated Now Playing status to '" + track + "'");
            } catch (Exception ex) {
                Logger.printException(() -> "Now Playing update failure", ex);
            }
        });
    }

    private static JSONObject createTrackMetadata(String artist, String track,
                                                     String songId, String album, int duration) throws Exception {
        JSONObject metadata = new JSONObject();
        metadata.put("artist_name", artist);
        metadata.put("track_name", track);
        if (album != null && !album.isBlank()) {
            metadata.put("release_name", album);
        }

        JSONObject info = new JSONObject();
        if (duration > 0) {
            info.put("duration_ms", (long) duration * 1000);
        }
        if (songId != null && !songId.isBlank()) {
            info.put("origin_url", "https://music.youtube.com/watch?v=" + songId);
        }
        info.put("submission_client", "Morphe");
        info.put("submission_client_version", CLIENT_VERSION);
        
        metadata.put("additional_info", info);
        return metadata;
    }

    private static boolean postRequest(String path, String token, String jsonBody) throws Exception {
        Utils.verifyOffMainThread();
        byte[] jsonBodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = Requester.openConnection(Settings.LISTENBRAINZ_BASE_URL.get() + path);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Authorization", "Token " + token);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setFixedLengthStreamingMode(jsonBodyBytes.length);
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBodyBytes);
        }

        final int code = conn.getResponseCode();
        if (code == 200) {
            return true;
        }
        String errResponse = Requester.parseErrorStringAndDisconnect(conn);
        Logger.printException(() -> "ListenBrainz server returned code: " + code + ". Response: " + errResponse);
        return false;
    }
}
