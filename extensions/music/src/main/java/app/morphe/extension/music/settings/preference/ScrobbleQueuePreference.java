/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1856
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

import app.morphe.extension.music.patches.scrobbling.ScrobbleQueue;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.CustomDialog;

@SuppressWarnings({"unused", "deprecation"})
public class ScrobbleQueuePreference extends Preference {

    public ScrobbleQueuePreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public ScrobbleQueuePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public ScrobbleQueuePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ScrobbleQueuePreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        setSelectable(true);
        setPersistent(false);
        updateSummary();
    }

    private void updateSummary() {
        int size = ScrobbleQueue.size();
        setSummary(size == 0
                ? str("morphe_music_scrobbling_queue_summary_empty")
                : str("morphe_music_scrobbling_queue_summary", size));
    }

    @Override
    protected void onClick() {
        int size = ScrobbleQueue.size();
        if (size == 0) return;

        CustomDialog.create(
                getContext(),
                str("morphe_music_scrobbling_queue_clear_title"),
                str("morphe_music_scrobbling_queue_clear_message", size),
                null,
                str("morphe_music_scrobbling_queue_clear_confirm"),
                () -> {
                    ScrobbleQueue.clear();
                    updateSummary();
                    Utils.showToastShort(str("morphe_music_scrobbling_queue_cleared_toast"));
                },
                null,
                null,
                null,
                true
        ).first.show();
    }
}
