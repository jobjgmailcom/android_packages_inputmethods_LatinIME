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

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Keyboard-sized clipboard history panel, kept separate from word prediction. */
public final class ClipboardHistoryPanel extends LinearLayout {
    public interface Listener {
        void onBack();
        void onPaste(ClipboardHistoryStore.Entry entry);
        void onTogglePinned(ClipboardHistoryStore.Entry entry);
        void onDelete(ClipboardHistoryStore.Entry entry);
        void onClear();
    }

    private final int mTextColor;
    private final int mKeyBackgroundColor;
    private final int mSpecialKeyBackgroundColor;
    private final LinearLayout mEntriesContainer;
    private final TextView mEmptyView;
    private final TextView mRecentTab;
    private final TextView mPinnedTab;
    private final TextView mClearButton;
    private final ArrayList<ClipboardHistoryStore.Entry> mEntries = new ArrayList<>();
    private Listener mListener;
    private boolean mShowPinned;

    public ClipboardHistoryPanel(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setClipToPadding(false);
        setPadding(dp(8), dp(4), dp(8), dp(4));

        final TypedArray colors = context.obtainStyledAttributes(
                new int[] { android.R.attr.textColorPrimary });
        final ColorStateList textColors = colors.getColorStateList(0);
        mTextColor = textColors == null ? Color.DKGRAY : textColors.getDefaultColor();
        mSpecialKeyBackgroundColor = Color.luminance(mTextColor) > 0.5f
                ? 0xFF455A64 : 0xFFD0D7DC;
        mKeyBackgroundColor = Color.luminance(mTextColor) > 0.5f
                ? 0xFF263238 : 0xFFE4E7EB;
        colors.recycle();

        final LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, dp(44)));

        final TextView backButton = makeButton("←", 24);
        backButton.setContentDescription(context.getString(R.string.clipboard_history_back));
        header.addView(backButton, new LayoutParams(dp(44), LayoutParams.MATCH_PARENT));
        backButton.setOnClickListener(v -> {
            if (mListener != null) mListener.onBack();
        });

        final TextView title = makeText(context.getString(R.string.clipboard_history_title), 18);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(title, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1.0f));

        mClearButton = makeButton(context.getString(R.string.clipboard_history_clear), 13);
        header.addView(mClearButton, new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT));
        mClearButton.setOnClickListener(v -> {
            if (mListener != null) mListener.onClear();
        });

        final LinearLayout tabs = new LinearLayout(context);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        addView(tabs, new LayoutParams(LayoutParams.MATCH_PARENT, dp(42)));
        mRecentTab = makeButton(context.getString(R.string.clipboard_history_recent), 14);
        mPinnedTab = makeButton(context.getString(R.string.clipboard_history_pinned), 14);
        final LayoutParams tabParams = new LayoutParams(0, dp(36), 1.0f);
        tabParams.setMargins(dp(4), dp(2), dp(4), dp(2));
        tabs.addView(mRecentTab, tabParams);
        final LayoutParams pinnedParams = new LayoutParams(0, dp(36), 1.0f);
        pinnedParams.setMargins(dp(4), dp(2), dp(4), dp(2));
        tabs.addView(mPinnedTab, pinnedParams);
        mRecentTab.setOnClickListener(v -> {
            mShowPinned = false;
            renderEntries();
        });
        mPinnedTab.setOnClickListener(v -> {
            mShowPinned = true;
            renderEntries();
        });

        final FrameLayout content = new FrameLayout(context);
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1.0f));
        final ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        content.addView(scrollView, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        mEntriesContainer = new LinearLayout(context);
        mEntriesContainer.setOrientation(VERTICAL);
        scrollView.addView(mEntriesContainer, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        mEmptyView = makeText(context.getString(R.string.clipboard_history_empty), 16);
        mEmptyView.setGravity(Gravity.CENTER);
        mEmptyView.setVisibility(GONE);
        content.addView(mEmptyView, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        renderEntries();
    }

    public void setListener(final Listener listener) {
        mListener = listener;
    }

    public void setEntries(final List<ClipboardHistoryStore.Entry> entries) {
        mEntries.clear();
        if (entries != null) mEntries.addAll(entries);
        renderEntries();
    }

    private void renderEntries() {
        mEntriesContainer.removeAllViews();
        final ArrayList<ClipboardHistoryStore.Entry> visibleEntries = new ArrayList<>();
        for (final ClipboardHistoryStore.Entry entry : mEntries) {
            if (entry.pinned == mShowPinned) visibleEntries.add(entry);
        }
        final boolean isEmpty = visibleEntries.isEmpty();
        mEmptyView.setText(getContext().getString(mShowPinned
                ? R.string.clipboard_history_empty_pinned : R.string.clipboard_history_empty));
        mEmptyView.setVisibility(isEmpty ? VISIBLE : GONE);
        mRecentTab.setBackground(makeTabBackground(!mShowPinned));
        mPinnedTab.setBackground(makeTabBackground(mShowPinned));

        for (final ClipboardHistoryStore.Entry entry : visibleEntries) {
            final LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(4), dp(4), dp(4));
            row.setBackground(makeRoundedBackground(mKeyBackgroundColor));
            final LayoutParams rowParams = new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(dp(2), dp(3), dp(2), dp(3));
            mEntriesContainer.addView(row, rowParams);

            final TextView content = makeText(preview(entry.text), 15);
            content.setGravity(Gravity.CENTER_VERTICAL);
            content.setMinHeight(dp(44));
            content.setMaxLines(2);
            content.setEllipsize(TextUtils.TruncateAt.END);
            content.setOnClickListener(v -> {
                if (mListener != null) mListener.onPaste(entry);
            });
            row.addView(content, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f));

            final TextView pin = makeButton(entry.pinned ? "★" : "☆", 20);
            pin.setContentDescription(getContext().getString(entry.pinned
                    ? R.string.clipboard_history_unpin : R.string.clipboard_history_pin));
            row.addView(pin, new LayoutParams(dp(40), dp(44)));
            pin.setOnClickListener(v -> {
                if (mListener != null) mListener.onTogglePinned(entry);
            });

            final TextView delete = makeButton("×", 22);
            delete.setContentDescription(getContext().getString(R.string.clipboard_history_delete));
            row.addView(delete, new LayoutParams(dp(40), dp(44)));
            delete.setOnClickListener(v -> {
                if (mListener != null) mListener.onDelete(entry);
            });
        }
    }

    private GradientDrawable makeTabBackground(final boolean selected) {
        return makeRoundedBackground(selected ? mSpecialKeyBackgroundColor : mKeyBackgroundColor);
    }

    private GradientDrawable makeRoundedBackground(final int color) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(dp(16));
        return drawable;
    }

    private TextView makeText(final CharSequence text, final int sizeSp) {
        final TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextColor(mTextColor);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private TextView makeButton(final CharSequence text, final int sizeSp) {
        final TextView view = makeText(text, sizeSp);
        view.setGravity(Gravity.CENTER);
        view.setBackground(makeRoundedBackground(mKeyBackgroundColor));
        view.setHapticFeedbackEnabled(false);
        return view;
    }

    private String preview(final String text) {
        final String oneLine = text.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() > 120 ? oneLine.substring(0, 119) + "…" : oneLine;
    }

    private int dp(final int value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
