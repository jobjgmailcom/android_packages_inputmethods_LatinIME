/*
 * Copyright (C) 2026 The LatinIME Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.inputmethod.latin;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.os.Build;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/** Private, on-device store for the optional plain-text clipboard history. */
public final class ClipboardHistoryStore {
    private static final String PREFERENCES_NAME = "latinime_clipboard_history";
    private static final String KEY_ENTRIES = "entries";
    private static final int MAX_HISTORY_ITEMS = 50;
    private static final int MAX_CLIP_LENGTH = 5000;
    private static final long RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1000L;

    public static final class Entry {
        public final String id;
        public final String text;
        public final long timestamp;
        public final boolean pinned;

        private Entry(final String id, final String text, final long timestamp,
                final boolean pinned) {
            this.id = id;
            this.text = text;
            this.timestamp = timestamp;
            this.pinned = pinned;
        }

        private Entry withPinned(final boolean isPinned) {
            return new Entry(id, text, timestamp, isPinned);
        }

        private Entry withTimestamp(final long updatedTimestamp) {
            return new Entry(id, text, updatedTimestamp, pinned);
        }
    }

    private final android.content.SharedPreferences mPreferences;

    public ClipboardHistoryStore(final Context context) {
        mPreferences = context.getApplicationContext().getSharedPreferences(
                PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    /** Adds only ordinary plain text. Sensitive, empty, oversized, and protected-field clips skip. */
    public synchronized boolean addClip(final ClipData clip, final boolean isSensitiveField) {
        if (clip == null || clip.getItemCount() == 0 || isSensitiveField) {
            return false;
        }
        final ClipDescription description = clip.getDescription();
        if (description == null || !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && description.getExtras() != null
                && description.getExtras().getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)) {
            return false;
        }
        final CharSequence clipText = clip.getItemAt(0).getText();
        if (TextUtils.isEmpty(clipText) || clipText.length() > MAX_CLIP_LENGTH) {
            return false;
        }
        final long clipTimestamp = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? description.getTimestamp() : 0L;
        final long timestamp = clipTimestamp > 0 ? clipTimestamp : System.currentTimeMillis();
        return addText(clipText.toString(), timestamp);
    }

    private synchronized boolean addText(final String text, final long timestamp) {
        if (TextUtils.isEmpty(text) || text.length() > MAX_CLIP_LENGTH) {
            return false;
        }
        final ArrayList<Entry> entries = readEntries();
        boolean wasPinned = false;
        String existingId = null;
        for (final Iterator<Entry> iterator = entries.iterator(); iterator.hasNext();) {
            final Entry entry = iterator.next();
            if (text.equals(entry.text)) {
                wasPinned = entry.pinned;
                existingId = entry.id;
                iterator.remove();
                break;
            }
        }
        entries.add(new Entry(existingId == null ? UUID.randomUUID().toString() : existingId,
                text, timestamp, wasPinned));
        pruneAndSort(entries, System.currentTimeMillis());
        writeEntries(entries);
        return true;
    }

    public synchronized List<Entry> getEntries() {
        final ArrayList<Entry> entries = readEntries();
        pruneAndSort(entries, System.currentTimeMillis());
        writeEntries(entries);
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public synchronized void togglePinned(final String id) {
        final ArrayList<Entry> entries = readEntries();
        for (int i = 0; i < entries.size(); i++) {
            final Entry entry = entries.get(i);
            if (entry.id.equals(id)) {
                entries.set(i, entry.withPinned(!entry.pinned));
                break;
            }
        }
        pruneAndSort(entries, System.currentTimeMillis());
        writeEntries(entries);
    }

    public synchronized void delete(final String id) {
        final ArrayList<Entry> entries = readEntries();
        for (final Iterator<Entry> iterator = entries.iterator(); iterator.hasNext();) {
            if (iterator.next().id.equals(id)) {
                iterator.remove();
                break;
            }
        }
        writeEntries(entries);
    }

    public synchronized void clear() {
        mPreferences.edit().remove(KEY_ENTRIES).apply();
    }

    private ArrayList<Entry> readEntries() {
        final ArrayList<Entry> entries = new ArrayList<>();
        final String json = mPreferences.getString(KEY_ENTRIES, "[]");
        try {
            final JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                final JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                final String text = item.optString("text", "");
                final String id = item.optString("id", "");
                final long timestamp = item.optLong("timestamp", 0L);
                if (!TextUtils.isEmpty(text) && text.length() <= MAX_CLIP_LENGTH
                        && !TextUtils.isEmpty(id) && timestamp > 0) {
                    entries.add(new Entry(id, text, timestamp, item.optBoolean("pinned", false)));
                }
            }
        } catch (final JSONException e) {
            // Ignore a damaged local history instead of preventing keyboard startup.
        }
        return entries;
    }

    private void writeEntries(final List<Entry> entries) {
        final JSONArray array = new JSONArray();
        for (final Entry entry : entries) {
            final JSONObject item = new JSONObject();
            try {
                item.put("id", entry.id);
                item.put("text", entry.text);
                item.put("timestamp", entry.timestamp);
                item.put("pinned", entry.pinned);
                array.put(item);
            } catch (final JSONException e) {
                // Strings and primitive values are serializable; skip only an invalid row.
            }
        }
        mPreferences.edit().putString(KEY_ENTRIES, array.toString()).apply();
    }

    private static void pruneAndSort(final ArrayList<Entry> entries, final long now) {
        for (final Iterator<Entry> iterator = entries.iterator(); iterator.hasNext();) {
            final Entry entry = iterator.next();
            if (!entry.pinned && now - entry.timestamp > RETENTION_MILLIS) {
                iterator.remove();
            }
        }
        Collections.sort(entries, new Comparator<Entry>() {
            @Override
            public int compare(final Entry first, final Entry second) {
                if (first.pinned != second.pinned) {
                    return first.pinned ? -1 : 1;
                }
                return Long.compare(second.timestamp, first.timestamp);
            }
        });
        while (entries.size() > MAX_HISTORY_ITEMS) {
            int removeIndex = -1;
            for (int i = entries.size() - 1; i >= 0; i--) {
                if (!entries.get(i).pinned) {
                    removeIndex = i;
                    break;
                }
            }
            if (removeIndex < 0) {
                removeIndex = entries.size() - 1;
            }
            entries.remove(removeIndex);
        }
    }
}
