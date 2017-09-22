/*
 * 	Copyright (c) 2017. Toshi Inc
 *
 * 	This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.toshi.view.custom;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;
import android.support.annotation.ColorRes;
import android.support.v4.content.ContextCompat;
import android.support.v4.util.Pair;
import android.text.InputFilter;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.android.flexbox.FlexboxLayout;
import com.jakewharton.rxbinding.widget.RxTextView;
import com.toshi.R;
import com.toshi.util.KeyboardUtil;
import com.toshi.util.LogUtil;
import com.toshi.util.SearchUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import rx.Observable;
import rx.Single;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.observables.ConnectableObservable;
import rx.schedulers.Schedulers;
import rx.subscriptions.CompositeSubscription;

public class PassphraseInputView extends FrameLayout {

    private static final String BUNDLE__SUPER_STATE = "super_state";
    private static final String BUNDLE__CURRENT_CELL = "current_cell";
    private static final String BUNDLE__WORD_LIST = "word_list";
    private static final String BUNDLE__IS_HIDDEN = "is_hidden";
    private static final String BUNDLE__APPROVED_WORDS = "approved_words";

    private int currentCell = 0;
    private boolean isHidden;
    private ArrayList<String> wordList;
    private ArrayList<String> passphraseList;

    private CompositeSubscription subscriptions;
    private OnPassphraseFinishListener onPassphraseFinishedListener;
    private OnPassphraseUpdateListener onPassphraseUpdateListener;

    public interface OnPassphraseFinishListener {
        void onPassphraseFinished(final List<String> passphrase);
    }

    public interface OnPassphraseUpdateListener {
        void onPassphraseUpdate(final int approvedWords);
    }

    public PassphraseInputView(Context context) {
        super(context);
        init();
    }

    public PassphraseInputView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PassphraseInputView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        inflate(getContext(), R.layout.view_passphrase_input, this);
        initSubscriptions();
    }

    private void initSubscriptions() {
        this.subscriptions = new CompositeSubscription();
    }

    public PassphraseInputView setOnPassphraseFinishListener(final OnPassphraseFinishListener listener) {
        this.onPassphraseFinishedListener = listener;
        return this;
    }

    public PassphraseInputView setOnPassphraseUpdateListener(final OnPassphraseUpdateListener listener) {
        this.onPassphraseUpdateListener = listener;
        return this;
    }

    public PassphraseInputView setWordList(final ArrayList<String> wordList) {
        this.wordList = wordList;
        initView();
        return this;
    }

    private void initView() {
        initPassphraseList();
        reAddWordsToInputViews();
        initClickListeners();
        initCellListeners();
        hidePassphrase(this.isHidden);
        initFocus();
    }

    private void initPassphraseList() {
        if (this.passphraseList != null) return;
        this.passphraseList = new ArrayList<>(12);
    }

    private void reAddWordsToInputViews() {
        if (this.passphraseList.size() == 0) return;
        final FlexboxLayout wrapper = findViewById(R.id.wrapper);
        for (int i = 0; i < this.passphraseList.size(); i++) {
            final SuggestionInputView inputView = (SuggestionInputView) wrapper.getChildAt(i);
            final String word = this.passphraseList.get(i);
            if (word != null) {
                inputView.showTagView(word);
            } else {
                inputView.showInputView(null);
            }
            inputView.setVisibility(VISIBLE);
        }
        checkIfPassphraseIsApproved();
    }

    private void initClickListeners() {
        findViewById(R.id.hidePassphrase).setOnClickListener(view -> handleHidePassphraseClicked());
        findViewById(R.id.wrapper).setOnClickListener(__ -> handleWrapperClicked());
        findViewById(R.id.wrapper).setOnLongClickListener(__ -> handleWrapperLongClicked());
    }

    private void handleHidePassphraseClicked() {
        this.isHidden = !this.isHidden;
        hidePassphrase(this.isHidden);
    }

    private void hidePassphrase(final boolean isHidden) {
        final FlexboxLayout wrapper = findViewById(R.id.wrapper);
        for (int i = 0; i < wrapper.getChildCount(); i++) {
            final SuggestionInputView suggestionInputView = (SuggestionInputView) wrapper.getChildAt(i);
            if (isHidden) {
                suggestionInputView.getTagView().hideText();
            } else {
                suggestionInputView.getTagView().showText();
            }
        }
    }

    //Go to the last cell available, unless the current cell is 0
    private void handleWrapperClicked() {
        final int lastContainedIndex = getLastWordContainedCellIndex();
        final int nextToLastContainedIndex = lastContainedIndex >= 11
                ? this.passphraseList.size() - 1
                : lastContainedIndex + 1;
        final int indexToGoTo = this.currentCell == 0
                ? this.currentCell
                : nextToLastContainedIndex;
        final SuggestionInputView inputView = getChild(indexToGoTo);
        goToCell(inputView);
    }

    private boolean handleWrapperLongClicked() {
        final ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        final ClipData clipData = clipboard.getPrimaryClip();
        if (clipData.getItemCount() == 0) return false;
        final ClipData.Item clipItem = clipData.getItemAt(0);
        if (clipItem == null || clipItem.getText() == null) return false;
        final List<String> wordList = Arrays.asList(clipItem.getText().toString().split(" "));
        if (wordList.size() > 12) {
            showErrorMessage(getContext().getString(R.string.paste_passphrase_error));
            return false;
        }
        pastePassphrase(wordList);
        return true;
    }

    private int getLastWordContainedCellIndex() {
        for (int i = this.passphraseList.size() - 1; i >= 0; i--) {
            final String word = this.passphraseList.get(i);
            if (word != null) return i;
        }
        return 0;
    }

    private void initCellListeners() {
        final FlexboxLayout wrapper = findViewById(R.id.wrapper);
        for (int i = 0; i < wrapper.getChildCount(); i++) {
            final SuggestionInputView inputView = (SuggestionInputView) wrapper.getChildAt(i);
            inputView.setOnClickListener(this::handleCellClicked);
            inputView.getWordView().setOnFocusChangeListener(this::handleCellFocusChanged);
        }
    }

    private void handleCellClicked(final View view) {
        final SuggestionInputView inputView = (SuggestionInputView) view;
        goToCell(inputView);
    }

    private void handleCellFocusChanged(final View view, final boolean hasFocus) {
        final SuggestionInputView inputView = (SuggestionInputView) view.getParent();
        if (hasFocus) {
            this.currentCell = getIndexOfView(inputView);
            final EditText wordView = inputView.getWordView();
            addBackspaceListener(wordView);
            addEnterClickedListener(wordView);
            addTextListeners(wordView);
        } else {
            clearView(inputView);
            inputView.setSuggestionAsWord();
            approveWord(inputView);
        }
    }

    private void addBackspaceListener(final EditText et) {
        et.setOnKeyListener((__, keyCode, keyEvent) -> handleKeyClicked(et, keyCode, keyEvent));
    }

    private void addEnterClickedListener(final EditText et) {
        et.setOnEditorActionListener((__, actionId, ___) -> handleEnterClicked(actionId));
    }

    //Listen to enter action
    private boolean handleEnterClicked(final int actionId) {
        if (actionId == EditorInfo.IME_ACTION_DONE) {
            final SuggestionInputView inputView = getChild(this.currentCell);
            inputView.clearFocus();
            return true;
        }
        return false;
    }

    private void initFocus() {
        final SuggestionInputView cell = getChild(this.currentCell);
        goToCell(cell);
    }

    //Listen to backspace action
    private boolean handleKeyClicked(final EditText et, final int keyCode, final KeyEvent keyEvent) {
        final boolean isFirstCell = this.currentCell == 0;
        final boolean isBackspace = keyCode == KeyEvent.KEYCODE_DEL;
        final boolean isViewEmpty = et.getText().length() == 0;
        final boolean isDownAction = keyEvent.getAction() == KeyEvent.ACTION_DOWN;
        if (!isFirstCell && isBackspace && isViewEmpty && isDownAction) {
            goToPreviousCell();
            return true;
        }
        return false;
    }

    private void goToPreviousCell() {
        final SuggestionInputView currentView = getChild(this.currentCell);
        //Hide the current cell if empty
        currentView.setVisibility(currentView.getWordView().length() == 0 ? GONE : VISIBLE);
        final SuggestionInputView previousView = getChild(this.currentCell - 1);
        goToCell(previousView);
    }

    private void addTextListeners(final EditText et) {
        final ConnectableObservable<String> textSource =
                RxTextView.textChanges(et)
                .skip(1)
                .map(CharSequence::toString)
                .publish();

        addSpaceHandler(et);

        final Subscription suggestionSub =
                textSource
                .filter(input -> input.length() > 0)
                .flatMap(this::getWordSuggestion)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.io())
                .subscribe(
                        this::handleWordSuggestion,
                        throwable -> LogUtil.e(getClass(), throwable.toString())
                );

        final Subscription uiSub =
                textSource
                .subscribe(
                        this::updateUi,
                        throwable ->  LogUtil.e(getClass(), throwable.toString())
                );

        final Subscription connectSub = textSource.connect();
        this.subscriptions.addAll(
                suggestionSub,
                uiSub,
                connectSub
        );
    }

    private void updateUi(final String input) {
        hideErrorMessage();
        clearSuggestion(input);
        clearHint(input);
    }

    private void addSpaceHandler(EditText et) {
        final InputFilter filter = (source, start, end, dest, dstart, dend) -> {
            for (int i = start; i < end; i++) {
                if (Character.isWhitespace(source.charAt(i))) {
                    tryAddSuggestion();
                    return "";
                }
            }
            return null;
        };
        et.setFilters(new InputFilter[] { filter });
    }

    private void tryAddSuggestion() {
        if (this.currentCell > 11) return;
        goToNextCell();
    }

    private void goToNextCell() {
        final SuggestionInputView currentView = getChild(this.currentCell);
        final String inputValueNoSpaces = currentView.getWordView().getText().toString().replace(" ", "");
        if (inputValueNoSpaces.length() == 0) return; //If the current cell is empty, don't do anything

        final SuggestionInputView nextView = getChild(this.currentCell + 1);
        if (nextView != null) {
            goToCell(nextView);
        } else {
            currentView.getWordView().clearFocus();
        }
    }

    private void goToCell(final SuggestionInputView cell) {
        if (cell == null) return;
        final String word = cell.getWord();
        setWordInCell(cell, word);
        cell.setVisibility(VISIBLE);
        setCursorAtEnd(cell.getWordView());
        cell.getWordView().requestFocus();
        KeyboardUtil.showKeyboard(cell.getWordView());
        hideErrorMessage();
    }

    private void setWordInCell(final SuggestionInputView cell, final String word) {
        //If word is null(word isn't approved), don't clear the text view
        if (word == null) return;
        cell.showInputView(word);
    }

    private Observable<String> getWordSuggestion(final String startOfWord) {
        return Observable.fromCallable(() -> {
            if (startOfWord.length() == 0) return null;
            return SearchUtil.findStringSuggestion(this.wordList, startOfWord);
        });
    }

    private void handleWordSuggestion(final String suggestion) {
        if (suggestion == null) {
            handleNoSuggestion();
            return;
        }

        final SuggestionInputView inputView = getChild(this.currentCell);
        inputView.setWordSuggestion(suggestion);
    }

    private void hideErrorMessage() {
        final TextView hidePassphrase = findViewById(R.id.hidePassphrase);
        final TextView errorView = findViewById(R.id.errorView);
        hidePassphrase.setVisibility(View.VISIBLE);
        errorView.setVisibility(View.GONE);
        updateInputUnderline(R.color.input_underline);
    }

    private void clearSuggestion(final String string) {
        if (string.length() > 0) return;
        final SuggestionInputView inputView = getChild(this.currentCell);
        inputView.clearSuggestion();
    }

    private void clearHint(final String input) {
        final TextView hint = findViewById(R.id.hint);
        if (this.passphraseList.size() == 0 && input.length() == 0) {
            hint.setVisibility(VISIBLE);
        } else {
            hint.setVisibility(GONE);
        }
    }

    private void clearView(final SuggestionInputView inputView) {
        clearSubscriptions();
        removeBackspaceListener(inputView.getWordView());
        removeEnterClickedListener(inputView.getWordView());
        clearSpacesInView(inputView.getWordView());
    }

    private void removeBackspaceListener(final EditText et) {
        et.setOnKeyListener(null);
    }

    private void removeEnterClickedListener(final EditText et) {
        et.setOnEditorActionListener(null);
    }

    private void setCursorAtEnd(final EditText cell) {
        final String cellString = cell.getText().toString();
        if (cellString.length() == 0) return;
        cell.setSelection(cellString.length());
    }

    private void handleNoSuggestion() {
        final SuggestionInputView currentCell = getChild(this.currentCell);
        final String passphraseInput = currentCell.getWordView().getText().toString();
        final String errorMessage = getContext().getString(R.string.no_suggestion_found, passphraseInput);
        showErrorMessage(errorMessage);
        currentCell.clearSuggestion();
    }

    private void showErrorMessage(final String string) {
        final TextView hidePassphrase = findViewById(R.id.hidePassphrase);
        final TextView errorView = findViewById(R.id.errorView);
        hidePassphrase.setVisibility(View.GONE);
        errorView.setVisibility(View.VISIBLE);
        errorView.setText(string);
        updateInputUnderline(R.color.error_color);
    }

    private void updateInputUnderline(final @ColorRes int colorRes) {
        final View underline = findViewById(R.id.underline);
        final int underlineColor = ContextCompat.getColor(getContext(), colorRes);
        underline.setBackgroundColor(underlineColor);
    }

    private void clearSpacesInView(final EditText et) {
        final String currentText = et.getText().toString();
        if (currentText.length() == 0) return;
        final String noSpacesText = currentText.replaceAll(" ","");
        et.setText(noSpacesText);
    }

    private void approveWord(final SuggestionInputView view) {
        final String word = view.getWord();
        final int cellIndex = getIndexOfView(view);

        if (word == null) {
            addWordToView(null, cellIndex);
            return;
        }

        final Subscription sub =
                findMatch(word)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        matchResult -> addWordToView(matchResult, cellIndex),
                        throwable -> LogUtil.d(getClass(), throwable.toString())
                );

        this.subscriptions.add(sub);
    }

    private int getIndexOfView(final SuggestionInputView view) {
        final FlexboxLayout wrapper = findViewById(R.id.wrapper);
        for (int i = 0; i < wrapper.getChildCount(); i++) {
            final View inputView = wrapper.getChildAt(i);
            if (inputView.getId() == view.getId()) {
                return i;
            }
        }
        return 0;
    }

    private Single<String> findMatch(final String wordToFind) {
        return Single.just(wordToFind)
                .flatMap(word -> Single.fromCallable(() -> SearchUtil.findMatch(this.wordList, word)));
    }

    private void addWordToView(final String matchResult, final int cellIndex) {
        final SuggestionInputView inputView = getChild(cellIndex);
        if (matchResult != null) {
            inputView.showTagView(matchResult);
        }

        addWordToPassphraseList(matchResult, cellIndex);
        checkIfPassphraseIsApproved();
    }

    private SuggestionInputView getChild(final int index) {
        final FlexboxLayout wrapper = findViewById(R.id.wrapper);
        return (SuggestionInputView) wrapper.getChildAt(index);
    }

    private void addWordToPassphraseList(final String word, final int cellIndex) {
        if (cellIndex < this.passphraseList.size()) {
            this.passphraseList.set(cellIndex, word);
        } else {
            this.passphraseList.add(cellIndex, word);
        }
    }

    private void checkIfPassphraseIsApproved() {
        if (isPassphraseApproved()) {
            this.onPassphraseFinishedListener.onPassphraseFinished(this.passphraseList);
        } else {
            this.onPassphraseUpdateListener.onPassphraseUpdate(getNumberApproveWords());
        }
    }

    private boolean isPassphraseApproved() {
        return this.passphraseList.size() == 12 && !this.passphraseList.contains(null);
    }

    private int getNumberApproveWords() {
        int numberOfApprovedWords = 0;
        for (final String word : this.passphraseList) {
            if (word != null) {
                numberOfApprovedWords++;
            }
        }
        return numberOfApprovedWords;
    }

    @Override
    public Parcelable onSaveInstanceState() {
        final Bundle bundle = new Bundle();
        bundle.putInt(BUNDLE__CURRENT_CELL, this.currentCell);
        bundle.putStringArrayList(BUNDLE__APPROVED_WORDS, this.passphraseList);
        bundle.putStringArrayList(BUNDLE__WORD_LIST, this.wordList);
        bundle.putBoolean(BUNDLE__IS_HIDDEN, this.isHidden);
        bundle.putParcelable(BUNDLE__SUPER_STATE, super.onSaveInstanceState());
        return bundle;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (state instanceof Bundle) {
            final Bundle bundle = (Bundle) state;
            this.currentCell = bundle.getInt(BUNDLE__CURRENT_CELL);
            this.passphraseList = bundle.getStringArrayList(BUNDLE__APPROVED_WORDS);
            this.wordList = bundle.getStringArrayList(BUNDLE__WORD_LIST);
            this.isHidden = bundle.getBoolean(BUNDLE__IS_HIDDEN);
            state = bundle.getParcelable(BUNDLE__SUPER_STATE);
        }
        super.onRestoreInstanceState(state);
        initView();
    }

    public List<String> getApprovedWordList() {
        return this.passphraseList;
    }

    public void pastePassphrase(final List<String> wordList) {
        initPassphraseList();
        this.passphraseList.clear();

        final Subscription sub =
                validatePastedPassphrase(wordList)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        this::addValidatedPassphraseToView,
                        throwable -> LogUtil.e(getClass(), throwable.toString())
                );

        this.subscriptions.add(sub);
    }

    private Single<List<Pair<Boolean, String>>> validatePastedPassphrase(final List<String> passphrase) {
        return Single.fromCallable(() -> {
            final List<Pair<Boolean, String>> validatedPassphrase = new ArrayList<>();
            for (final String word : passphrase) {
                final String result = SearchUtil.findMatch(this.wordList, word);
                if (result != null) {
                    validatedPassphrase.add(new Pair<>(true, word));
                } else {
                    validatedPassphrase.add(new Pair<>(false, word));
                }
            }
            return validatedPassphrase;
        });
    }

    private void addValidatedPassphraseToView(final List<Pair<Boolean, String>> validatedPassphrase) {
        if (validatedPassphrase.size() == 0) return;
        final FlexboxLayout wrapper = findViewById(R.id.wrapper);
        for (int i = 0; i < validatedPassphrase.size(); i++) {
            final SuggestionInputView inputView = (SuggestionInputView) wrapper.getChildAt(i);
            final String word = validatedPassphrase.get(i).second;
            final boolean isApproved = validatedPassphrase.get(i).first;
            if (isApproved) {
                inputView.showTagView(word);
                addWordToPassphraseList(word, i);
            } else {
                inputView.showInputView(word);
                addWordToPassphraseList(null, i);
            }
            inputView.setVisibility(VISIBLE);
        }

        checkIfPassphraseIsApproved();
        this.currentCell = validatedPassphrase.size() - 1;
        findViewById(R.id.hint).setVisibility(GONE);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        clearSubscriptions();
    }

    private void clearSubscriptions() {
        this.subscriptions.clear();
    }
}
