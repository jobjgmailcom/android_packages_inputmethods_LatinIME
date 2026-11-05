/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.inputmethod.latin.settings;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;
import android.text.TextUtils;
import android.widget.Toast;

import com.android.inputmethod.dictionarypack.DictionarySettingsActivity;
import com.android.inputmethod.dictionarypack.DictionaryPackConstants;
import com.android.inputmethod.latin.R;
import com.android.inputmethod.latin.ReadOnlyBinaryDictionary;
import com.android.inputmethod.latin.makedict.DictionaryHeader;
import com.android.inputmethod.latin.utils.DictionaryInfoUtils;
import com.android.inputmethod.compat.UserDictionaryCompatUtils;
import com.android.inputmethod.latin.personalization.PersonalizationHelper;
import com.android.inputmethod.latin.permissions.PermissionsManager;
import com.android.inputmethod.latin.permissions.PermissionsUtil;
import com.android.inputmethod.latin.userdictionary.UserDictionaryList;
import com.android.inputmethod.latin.userdictionary.UserDictionarySettings;

import java.util.TreeSet;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * "Text correction" settings sub screen.
 *
 * This settings sub screen handles the following text correction preferences.
 * - Personal dictionary
 * - Add-on dictionaries
 * - Block offensive words
 * - Auto-correction
 * - Show correction suggestions
 * - Personalized suggestions
 * - Suggest Contact names
 * - Next-word suggestions
 */
public final class CorrectionSettingsFragment extends SubScreenFragment
    implements SharedPreferences.OnSharedPreferenceChangeListener,
            PermissionsManager.PermissionsResultCallback {

    private static final boolean DBG_USE_INTERNAL_PERSONAL_DICTIONARY_SETTINGS = false;
    private static final boolean USE_INTERNAL_PERSONAL_DICTIONARY_SETTINGS =
            DBG_USE_INTERNAL_PERSONAL_DICTIONARY_SETTINGS
            || Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN_MR2;

    private SwitchPreferenceCompat mUseContactsPreference;
    private static final int REQUEST_IMPORT_DICTIONARY = 4107;
    private static final int REQUEST_BACKUP_LEARNED_WORDS = 4108;
    private static final int REQUEST_RESTORE_LEARNED_WORDS = 4109;
    private static final int REQUEST_IMPORT_BINARY_DICTIONARY = 4110;
    private static final int MAX_IMPORTED_WORDS = 10000;
    private static final long MAX_LEARNED_BACKUP_BYTES = 80L * 1024 * 1024;
    private static final long MAX_IMPORTED_BINARY_DICTIONARY_BYTES = 80L * 1024 * 1024;

    @Override
    public void onCreate(final Bundle icicle) {
        super.onCreate(icicle);
        addPreferencesFromResource(R.xml.prefs_screen_correction);

        final Context context = getActivity();
        final PackageManager pm = context.getPackageManager();

        final Preference dictionaryLink = findPreference(Settings.PREF_CONFIGURE_DICTIONARIES_KEY);
        final Intent intent = dictionaryLink.getIntent();
        intent.setClassName(context.getPackageName(), DictionarySettingsActivity.class.getName());
        final int number = pm.queryIntentActivities(intent, 0).size();
        if (0 >= number) {
            removePreference(Settings.PREF_CONFIGURE_DICTIONARIES_KEY);
        }

        final Preference editPersonalDictionary =
                findPreference(Settings.PREF_EDIT_PERSONAL_DICTIONARY);
        final Intent editPersonalDictionaryIntent = editPersonalDictionary.getIntent();
        final ResolveInfo ri = USE_INTERNAL_PERSONAL_DICTIONARY_SETTINGS ? null
                : pm.resolveActivity(
                        editPersonalDictionaryIntent, PackageManager.MATCH_DEFAULT_ONLY);
        if (ri == null) {
            overwriteUserDictionaryPreference(editPersonalDictionary);
        }

        mUseContactsPreference = (SwitchPreferenceCompat) findPreference(
                Settings.PREF_KEY_USE_CONTACTS_DICT);
        turnOffUseContactsIfNoPermission();

        final Preference importPreference = findPreference("pref_import_personal_dictionary");
        if (importPreference != null) {
            importPreference.setOnPreferenceClickListener(preference -> {
                final Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                picker.addCategory(Intent.CATEGORY_OPENABLE);
                picker.setType("text/*");
                picker.putExtra(Intent.EXTRA_MIME_TYPES,
                        new String[] {"text/plain", "text/csv", "text/tab-separated-values"});
                startActivityForResult(picker, REQUEST_IMPORT_DICTIONARY);
                return true;
            });
        }
        final Preference binaryImportPreference = findPreference("pref_import_binary_dictionary");
        if (binaryImportPreference != null) {
            binaryImportPreference.setOnPreferenceClickListener(preference -> {
                final Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                picker.addCategory(Intent.CATEGORY_OPENABLE);
                picker.setType("*/*");
                startActivityForResult(picker, REQUEST_IMPORT_BINARY_DICTIONARY);
                return true;
            });
        }
        final Preference backupLearnedPreference = findPreference("backup_learned_words");
        if (backupLearnedPreference != null) {
            backupLearnedPreference.setOnPreferenceClickListener(preference -> {
                final Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                create.addCategory(Intent.CATEGORY_OPENABLE);
                create.setType("application/zip");
                create.putExtra(Intent.EXTRA_TITLE, "LatinIME-palabras-aprendidas.zip");
                startActivityForResult(create, REQUEST_BACKUP_LEARNED_WORDS);
                return true;
            });
        }
        final Preference restoreLearnedPreference = findPreference("restore_learned_words");
        if (restoreLearnedPreference != null) {
            restoreLearnedPreference.setOnPreferenceClickListener(preference -> {
                final Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                open.addCategory(Intent.CATEGORY_OPENABLE);
                open.setType("application/zip");
                startActivityForResult(open, REQUEST_RESTORE_LEARNED_WORDS);
                return true;
            });
        }
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode == REQUEST_IMPORT_DICTIONARY) {
            importDictionaryFile(data.getData());
        } else if (requestCode == REQUEST_IMPORT_BINARY_DICTIONARY) {
            importBinaryDictionaryFile(data.getData());
        } else if (requestCode == REQUEST_BACKUP_LEARNED_WORDS) {
            backupLearnedWords(data.getData());
        } else if (requestCode == REQUEST_RESTORE_LEARNED_WORDS) {
            restoreLearnedWords(data.getData());
        }
    }

    private void backupLearnedWords(final Uri uri) {
        final Context context = getActivity().getApplicationContext();
        new Thread(() -> {
            int result = R.string.learned_backup_failed;
            try {
                PersonalizationHelper.flushAllUserHistoryDictionaries();
                final File filesDir = context.getFilesDir();
                final File[] dictionaries = filesDir == null ? null : filesDir.listFiles(
                        (dir, name) -> name.startsWith("UserHistoryDictionary")
                                && name.endsWith(".dict"));
                if (dictionaries == null || dictionaries.length == 0) {
                    result = R.string.learned_backup_empty;
                } else {
                    final OutputStream output = context.getContentResolver().openOutputStream(uri);
                    if (output == null) throw new IOException("Cannot open backup destination");
                    try (ZipOutputStream zip = new ZipOutputStream(output)) {
                        final byte[] buffer = new byte[8192];
                        long totalBytes = 0;
                        for (final File dictionary : dictionaries) {
                            totalBytes += dictionary.length();
                            if (totalBytes > MAX_LEARNED_BACKUP_BYTES) {
                                throw new IOException("Learned history backup is too large");
                            }
                            zip.putNextEntry(new ZipEntry(dictionary.getName()));
                            try (InputStream input = new FileInputStream(dictionary)) {
                                int count;
                                while ((count = input.read(buffer)) != -1) {
                                    zip.write(buffer, 0, count);
                                }
                            }
                            zip.closeEntry();
                        }
                    }
                    result = R.string.learned_backup_success;
                }
            } catch (final IOException | SecurityException e) {
                result = R.string.learned_backup_failed;
            }
            showBackgroundToast(context, result);
        }, "LatinIME-learned-backup").start();
    }

    private void restoreLearnedWords(final Uri uri) {
        final Context context = getActivity().getApplicationContext();
        new Thread(() -> {
            int result = R.string.learned_restore_failed;
            final File stage = new File(context.getCacheDir(), "learned-history-restore");
            try {
                deleteRecursively(stage);
                if (!stage.mkdirs() && !stage.isDirectory()) {
                    throw new IOException("Cannot create restore staging directory");
                }
                final InputStream input = context.getContentResolver().openInputStream(uri);
                if (input == null) throw new IOException("Cannot open backup file");
                int fileCount = 0;
                long totalBytes = 0;
                final byte[] buffer = new byte[8192];
                try (ZipInputStream zip = new ZipInputStream(input)) {
                    ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null) {
                        if (entry.isDirectory()) continue;
                        final String name = entry.getName();
                        final File leaf = new File(name);
                        if (!name.equals(leaf.getName()) || !name.startsWith("UserHistoryDictionary")
                                || !name.endsWith(".dict") || name.contains("..")) {
                            throw new IOException("Invalid learned history entry");
                        }
                        if (++fileCount > 64) throw new IOException("Too many dictionary files");
                        final File stagedFile = new File(stage, name + ".tmp");
                        try (FileOutputStream output = new FileOutputStream(stagedFile)) {
                            int count;
                            while ((count = zip.read(buffer)) != -1) {
                                totalBytes += count;
                                if (totalBytes > MAX_LEARNED_BACKUP_BYTES) {
                                    throw new IOException("Learned history backup is too large");
                                }
                                output.write(buffer, 0, count);
                            }
                        }
                        zip.closeEntry();
                    }
                }
                final File[] stagedFiles = stage.listFiles();
                if (stagedFiles == null || stagedFiles.length == 0) {
                    result = R.string.learned_restore_empty;
                } else {
                    PersonalizationHelper.closeAllUserHistoryDictionariesWithoutDeleting();
                    final File filesDir = context.getFilesDir();
                    for (final File stagedFile : stagedFiles) {
                        final String name = stagedFile.getName().substring(
                                0, stagedFile.getName().length() - 4);
                        installStagedDictionary(stagedFile, new File(filesDir, name));
                    }
                    result = R.string.learned_restore_success;
                }
            } catch (final IOException | SecurityException e) {
                result = R.string.learned_restore_failed;
            } finally {
                deleteRecursively(stage);
            }
            showBackgroundToast(context, result);
        }, "LatinIME-learned-restore").start();
    }

    private static void installStagedDictionary(final File stagedFile, final File destination)
            throws IOException {
        final File replacement = new File(destination.getParentFile(), destination.getName() + ".new");
        final File previous = new File(destination.getParentFile(), destination.getName() + ".old");
        copyFile(stagedFile, replacement);
        if (previous.exists()) deleteRecursively(previous);
        final boolean hadPrevious = destination.exists();
        if (hadPrevious && !destination.renameTo(previous)) {
            throw new IOException("Cannot stage existing learned dictionary");
        }
        if (!replacement.renameTo(destination)) {
            if (hadPrevious) previous.renameTo(destination);
            throw new IOException("Cannot install learned dictionary");
        }
        if (hadPrevious) previous.delete();
    }

    private static void copyFile(final File source, final File destination) throws IOException {
        final byte[] buffer = new byte[8192];
        try (InputStream input = new FileInputStream(source);
                OutputStream output = new FileOutputStream(destination)) {
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
    }

    private static void deleteRecursively(final File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            final File[] children = file.listFiles();
            if (children != null) for (final File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private void showBackgroundToast(final Context context, final int message) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }

    private void importDictionaryFile(final Uri uri) {
        int imported = 0;
        try (InputStream input = getActivity().getContentResolver().openInputStream(uri);
                BufferedReader reader = new BufferedReader(new InputStreamReader(input,
                        StandardCharsets.UTF_8))) {
            final Locale locale = getResources().getConfiguration().getLocales().isEmpty()
                    ? Locale.getDefault()
                    : getResources().getConfiguration().getLocales().get(0);
            String line;
            int linesRead = 0;
            while ((line = reader.readLine()) != null && linesRead++ < MAX_IMPORTED_WORDS) {
                if (line.startsWith("\uFEFF")) {
                    line = line.substring(1);
                }
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                final String[] fields = line.split(line.indexOf('\t') >= 0 ? "\\t" : ",", 2);
                final String word = unquote(fields[0].trim());
                final String shortcut = fields.length > 1 ? unquote(fields[1].trim()) : "";
                if (word.isEmpty() || word.length() > 64 || word.indexOf('\n') >= 0) {
                    continue;
                }
                UserDictionaryCompatUtils.addWord(getActivity(), word, 250, shortcut, locale);
                imported++;
            }
            if (imported > 0) {
                Toast.makeText(getActivity(), getString(R.string.import_dictionary_result, imported),
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(getActivity(), R.string.import_dictionary_empty,
                        Toast.LENGTH_LONG).show();
            }
        } catch (final IOException | SecurityException e) {
            Toast.makeText(getActivity(), R.string.import_dictionary_failed,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void importBinaryDictionaryFile(final Uri uri) {
        final Context context = getActivity().getApplicationContext();
        new Thread(() -> {
            int result = R.string.import_binary_dictionary_failed;
            File temporaryFile = null;
            try {
                temporaryFile = File.createTempFile(
                        "latinime-import-", ".dict", context.getCacheDir());
                final InputStream source = context.getContentResolver().openInputStream(uri);
                if (source == null) throw new IOException("Cannot open dictionary file");
                long totalBytes = 0;
                final byte[] buffer = new byte[8192];
                try (InputStream input = source;
                        FileOutputStream output = new FileOutputStream(temporaryFile)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        totalBytes += count;
                        if (totalBytes > MAX_IMPORTED_BINARY_DICTIONARY_BYTES) {
                            throw new IOException("Dictionary file is too large");
                        }
                        output.write(buffer, 0, count);
                    }
                }
                final DictionaryHeader header = DictionaryInfoUtils.getDictionaryFileHeaderOrNull(
                        temporaryFile, 0, temporaryFile.length());
                if (header == null || header.mIdString.isEmpty()) {
                    throw new IOException("Invalid dictionary header");
                }
                final String dictionaryVersion = header.mDictionaryOptions.mAttributes.get(
                        DictionaryHeader.DICTIONARY_VERSION_KEY);
                if (dictionaryVersion == null || Integer.parseInt(dictionaryVersion) < 18) {
                    throw new IOException("Unsupported dictionary version");
                }
                final Locale locale = Locale.forLanguageTag(header.mLocaleString.replace('_', '-'));
                if (TextUtils.isEmpty(locale.getLanguage()) || "und".equals(locale.getLanguage())) {
                    throw new IOException("Invalid dictionary locale");
                }
                final ReadOnlyBinaryDictionary validator = new ReadOnlyBinaryDictionary(
                        temporaryFile.getAbsolutePath(), 0, temporaryFile.length(),
                        false /* useFullEditDistance */, locale, "import_validation");
                final boolean valid;
                try {
                    valid = validator.isValidDictionary();
                } finally {
                    validator.close();
                }
                if (!valid) throw new IOException("Unsupported binary dictionary format");

                // Keep imported dictionaries separate so an experimental file never replaces
                // the built-in language dictionary.
                final String importedId = "imported_"
                        + UUID.randomUUID().toString().replace("-", "") + ":"
                        + locale.toString().toLowerCase(Locale.ROOT);
                final File destination = new File(DictionaryInfoUtils.getCacheFileName(
                        importedId, locale.toString(), context));
                installStagedDictionary(temporaryFile, destination);
                context.sendBroadcast(new Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION));
                result = R.string.import_binary_dictionary_success;
            } catch (final IOException | SecurityException | NumberFormatException e) {
                result = R.string.import_binary_dictionary_failed;
            } finally {
                if (temporaryFile != null) temporaryFile.delete();
            }
            showBackgroundToast(context, result);
        }, "LatinIME-dictionary-import").start();
    }

    private static String unquote(final String value) {
        if (value.length() >= 2 && value.charAt(0) == '"'
                && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1).replace("\"\"", "\"").trim();
        }
        return value;
    }

    private void overwriteUserDictionaryPreference(final Preference userDictionaryPreference) {
        final Activity activity = getActivity();
        final TreeSet<String> localeList = UserDictionaryList.getUserDictionaryLocalesSet(activity);
        if (null == localeList) {
            // The locale list is null if and only if the user dictionary service is
            // not present or disabled. In this case we need to remove the preference.
            getPreferenceScreen().removePreference(userDictionaryPreference);
        } else if (localeList.size() <= 1) {
            userDictionaryPreference.setFragment(UserDictionarySettings.class.getName());
            // If the size of localeList is 0, we don't set the locale parameter in the
            // extras. This will be interpreted by the UserDictionarySettings class as
            // meaning "the current locale".
            // Note that with the current code for UserDictionaryList#getUserDictionaryLocalesSet()
            // the locale list always has at least one element, since it always includes the current
            // locale explicitly. @see UserDictionaryList.getUserDictionaryLocalesSet().
            if (localeList.size() == 1) {
                final String locale = (String)localeList.toArray()[0];
                userDictionaryPreference.getExtras().putString("locale", locale);
            }
        } else {
            userDictionaryPreference.setFragment(UserDictionaryList.class.getName());
        }
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
        if (!TextUtils.equals(key, Settings.PREF_KEY_USE_CONTACTS_DICT)) {
            return;
        }
        if (!sharedPreferences.getBoolean(key, false)) {
            // don't care if the preference is turned off.
            return;
        }

        // Check for permissions.
        if (PermissionsUtil.checkAllPermissionsGranted(
                getActivity() /* context */, Manifest.permission.READ_CONTACTS)) {
            return; // all permissions granted, no need to request permissions.
        }

        PermissionsManager.get(getActivity() /* context */).requestPermissions(
                this /* PermissionsResultCallback */,
                getActivity() /* activity */,
                Manifest.permission.READ_CONTACTS);
    }

    @Override
    public void onRequestPermissionsResult(boolean allGranted) {
        turnOffUseContactsIfNoPermission();
    }

    private void turnOffUseContactsIfNoPermission() {
        if (!PermissionsUtil.checkAllPermissionsGranted(
                getActivity(), Manifest.permission.READ_CONTACTS)) {
            mUseContactsPreference.setChecked(false);
        }
    }
}
