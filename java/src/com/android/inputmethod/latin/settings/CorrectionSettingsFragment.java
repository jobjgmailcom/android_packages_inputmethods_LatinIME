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
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;
import android.text.TextUtils;
import android.widget.Toast;

import com.android.inputmethod.dictionarypack.DictionarySettingsActivity;
import com.android.inputmethod.latin.R;
import com.android.inputmethod.compat.UserDictionaryCompatUtils;
import com.android.inputmethod.latin.permissions.PermissionsManager;
import com.android.inputmethod.latin.permissions.PermissionsUtil;
import com.android.inputmethod.latin.userdictionary.UserDictionaryList;
import com.android.inputmethod.latin.userdictionary.UserDictionarySettings;

import java.util.TreeSet;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

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
    private static final int MAX_IMPORTED_WORDS = 10000;

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
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT_DICTIONARY || resultCode != Activity.RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        importDictionaryFile(data.getData());
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
