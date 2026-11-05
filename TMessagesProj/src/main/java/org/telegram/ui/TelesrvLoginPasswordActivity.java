package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SRPHelper;
import org.telegram.messenger.TelesrvLoginConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_telesrv;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.OutlineTextContainerView;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

// Set, change, remove or reset the telesrv login password of the signed-in account
// (TgSever docs/password-login.md, "Managing the login password" and "Forgotten password").
// Every action starts from a fresh telesrv.getMyLoginPassword: its SRP challenge is single-use
// and bound to the connection, so it is never cached between attempts.
public class TelesrvLoginPasswordActivity extends BaseFragment {

    private static final int MODE_LOADING = 0;
    private static final int MODE_OVERVIEW = 1; // a password is set
    private static final int MODE_SET = 2;      // no password yet
    private static final int MODE_CHANGE = 3;
    private static final int MODE_REMOVE = 4;
    private static final int MODE_RESET = 5;    // new password without the old one

    public interface StateListener {
        void onLoginPasswordState(boolean hasPassword);
    }

    private final StateListener stateListener;
    private Boolean hasPassword;
    private int mode = MODE_LOADING;
    private boolean requestInProgress;

    private LinearLayout contentLayout;
    private OutlineTextContainerView currentOutline;
    private OutlineTextContainerView newOutline;
    private OutlineTextContainerView confirmOutline;
    private EditTextBoldCursor currentField;
    private EditTextBoldCursor newField;
    private EditTextBoldCursor confirmField;
    private AlertDialog progressDialog;

    // hasPassword may be null: the page loads the state itself.
    public TelesrvLoginPasswordActivity(Boolean hasPassword, StateListener stateListener) {
        this.hasPassword = hasPassword;
        this.stateListener = stateListener;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.TelesrvLoginPassword));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1 && onBackPressed(true)) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        frameLayout.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        contentLayout = new LinearLayout(context);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(contentLayout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        fragmentView = frameLayout;
        if (hasPassword == null) {
            showMode(MODE_LOADING);
            loadState();
        } else {
            showMode(hasPassword ? MODE_OVERVIEW : MODE_SET);
        }
        return fragmentView;
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (mode == MODE_CHANGE || mode == MODE_REMOVE || mode == MODE_RESET) {
            if (invoked) {
                showMode(MODE_OVERVIEW);
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        hideProgress();
    }

    private void loadState() {
        TL_telesrv.getMyLoginPassword req = new TL_telesrv.getMyLoginPassword();
        int reqId = getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TL_account.Password) {
                setHasPassword(((TL_account.Password) response).has_password);
                showMode(hasPassword ? MODE_OVERVIEW : MODE_SET);
            } else if (!handleCommonError(error)) {
                showError(error);
                finishFragment();
            }
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
        getConnectionsManager().bindRequestToGuid(reqId, classGuid);
    }

    private void setHasPassword(boolean value) {
        hasPassword = value;
        if (stateListener != null) {
            stateListener.onLoginPasswordState(value);
        }
    }

    // region UI

    private void showMode(int newMode) {
        mode = newMode;
        if (contentLayout == null) {
            return;
        }
        Context context = contentLayout.getContext();
        contentLayout.removeAllViews();
        currentOutline = newOutline = confirmOutline = null;
        currentField = newField = confirmField = null;
        if (getParentActivity() != null && fragmentView != null) {
            AndroidUtilities.hideKeyboard(fragmentView.findFocus());
        }

        switch (newMode) {
            case MODE_LOADING:
                addTitle(context, getString(R.string.TelesrvLoginPassword));
                addDescription(context, getString(R.string.Loading));
                break;
            case MODE_OVERVIEW:
                addTitle(context, getString(R.string.TelesrvLoginPasswordOnTitle));
                addDescription(context, getString(R.string.TelesrvLoginPasswordOnInfo));
                addButton(context, getString(R.string.TelesrvLoginPasswordChange), true, () -> showMode(MODE_CHANGE));
                addButton(context, getString(R.string.TelesrvLoginPasswordRemove), false, () -> showMode(MODE_REMOVE));
                addButton(context, getString(R.string.TelesrvLoginPasswordForgot), false, this::showForgotAlert);
                break;
            case MODE_SET:
                addTitle(context, getString(R.string.TelesrvLoginPasswordSetTitle));
                addDescription(context, getString(R.string.TelesrvLoginPasswordSetInfo));
                addNewPasswordFields(context);
                addButton(context, getString(R.string.TelesrvLoginPasswordSet), true, this::submit);
                break;
            case MODE_CHANGE:
                addTitle(context, getString(R.string.TelesrvLoginPasswordChange));
                addDescription(context, getString(R.string.TelesrvLoginPasswordChangeInfo));
                addCurrentPasswordField(context, false);
                addNewPasswordFields(context);
                addButton(context, getString(R.string.TelesrvLoginPasswordChange), true, this::submit);
                break;
            case MODE_REMOVE:
                addTitle(context, getString(R.string.TelesrvLoginPasswordRemove));
                addDescription(context, getString(R.string.TelesrvLoginPasswordRemoveInfo));
                addCurrentPasswordField(context, true);
                addButton(context, getString(R.string.TelesrvLoginPasswordRemove), true, this::submit);
                break;
            case MODE_RESET:
                addTitle(context, getString(R.string.TelesrvLoginPasswordReset));
                addDescription(context, getString(R.string.TelesrvLoginPasswordResetInfo));
                addNewPasswordFields(context);
                addButton(context, getString(R.string.TelesrvLoginPasswordReset), true, this::submit);
                break;
        }

        EditTextBoldCursor first = currentField != null ? currentField : newField;
        if (first != null) {
            AndroidUtilities.runOnUIThread(() -> {
                if (first.isAttachedToWindow()) {
                    first.requestFocus();
                    AndroidUtilities.showKeyboard(first);
                }
            }, 200);
        }
    }

    private void addTitle(Context context, String text) {
        TextView titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setGravity(Gravity.CENTER_HORIZONTAL);
        titleView.setText(text);
        contentLayout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 32, 32, 32, 0));
    }

    private void addDescription(Context context, String text) {
        TextView descriptionView = new TextView(context);
        descriptionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        descriptionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6));
        descriptionView.setGravity(Gravity.CENTER_HORIZONTAL);
        descriptionView.setLineSpacing(dp(2), 1.0f);
        descriptionView.setText(text);
        contentLayout.addView(descriptionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 32, 10, 32, 8));
    }

    private void addCurrentPasswordField(Context context, boolean last) {
        currentOutline = new OutlineTextContainerView(context);
        currentOutline.setText(getString(R.string.TelesrvLoginPasswordCurrent));
        currentField = createField(context, currentOutline, last);
        contentLayout.addView(currentOutline, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 16, 24, 0));
    }

    private void addNewPasswordFields(Context context) {
        newOutline = new OutlineTextContainerView(context);
        newOutline.setText(getString(R.string.TelesrvLoginPasswordNew));
        newField = createField(context, newOutline, false);
        contentLayout.addView(newOutline, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 16, 24, 0));

        confirmOutline = new OutlineTextContainerView(context);
        confirmOutline.setText(getString(R.string.ReEnterPassword));
        confirmField = createField(context, confirmOutline, true);
        contentLayout.addView(confirmOutline, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 16, 24, 0));
    }

    private EditTextBoldCursor createField(Context context, OutlineTextContainerView outline, boolean last) {
        EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setCursorSize(dp(20));
        field.setCursorWidth(1.5f);
        field.setBackground(null);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        field.setMaxLines(1);
        field.setSingleLine(true);
        int padding = dp(16);
        field.setPadding(padding, padding, padding, padding);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setTransformationMethod(PasswordTransformationMethod.getInstance());
        field.setTypeface(Typeface.DEFAULT);
        field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        field.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        field.setImeOptions((last ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_NEXT) | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        if (last) {
            field.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    submit();
                    return true;
                }
                return false;
            });
        }
        field.setOnFocusChangeListener((v, hasFocus) -> outline.animateSelection(hasFocus ? 1f : 0f));
        outline.attachEditText(field);
        outline.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        outline.updateColor();
        return field;
    }

    private void addButton(Context context, String text, boolean filled, Runnable onClick) {
        ButtonWithCounterView button = new ButtonWithCounterView(context, filled, getResourceProvider());
        button.setText(text, false);
        button.setOnClickListener(v -> onClick.run());
        contentLayout.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 24, filled ? 24 : 8, 24, 0));
    }

    private void onFieldError(OutlineTextContainerView outline, EditTextBoldCursor field) {
        if (outline == null || field == null) {
            return;
        }
        AndroidUtilities.shakeViewSpring(outline, 3.5f);
        outline.animateError(1f);
        field.requestFocus();
        AndroidUtilities.runOnUIThread(() -> outline.animateError(0f), 2000);
    }

    private void showProgress() {
        if (getParentActivity() == null || progressDialog != null) {
            return;
        }
        progressDialog = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progressDialog.setCanCancel(false);
        progressDialog.show();
    }

    private void hideProgress() {
        if (progressDialog != null) {
            try {
                progressDialog.dismiss();
            } catch (Exception e) {
                FileLog.e(e);
            }
            progressDialog = null;
        }
    }

    private void showAlert(String text) {
        if (getParentActivity() == null) {
            return;
        }
        showDialog(AlertsCreator.createSimpleAlert(getParentActivity(), getString(R.string.TelesrvLoginPassword), text).create());
    }

    private void showForgotAlert() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.TelesrvLoginPasswordForgot));
        builder.setMessage(getString(R.string.TelesrvLoginPasswordForgotInfo));
        builder.setPositiveButton(getString(R.string.TelesrvLoginPasswordReset), (dialog, which) -> showMode(MODE_RESET));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    // endregion

    // region requests

    private void submit() {
        if (requestInProgress) {
            return;
        }
        final int submitMode = mode;
        final String current = currentField != null ? currentField.getText().toString() : null;
        final String newPassword = newField != null ? newField.getText().toString() : null;

        if (currentField != null && current.isEmpty()) {
            onFieldError(currentOutline, currentField);
            return;
        }
        if (newField != null) {
            if (newPassword.isEmpty()) {
                onFieldError(newOutline, newField);
                return;
            }
            if (!newPassword.equals(confirmField.getText().toString())) {
                onFieldError(confirmOutline, confirmField);
                showAlert(getString(R.string.PasswordDoNotMatch));
                return;
            }
            if (submitMode == MODE_CHANGE && newPassword.equals(current)) {
                onFieldError(newOutline, newField);
                showAlert(getString(R.string.TelesrvLoginPasswordSame));
                return;
            }
        }

        requestInProgress = true;
        showProgress();
        // A fresh challenge for every attempt: the previous one is burned by any answer.
        TL_telesrv.getMyLoginPassword req = new TL_telesrv.getMyLoginPassword();
        getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(response instanceof TL_account.Password)) {
                finishRequest();
                if (!handleCommonError(error)) {
                    showError(error);
                }
                return;
            }
            TL_account.Password password = (TL_account.Password) response;
            if (password.has_password != (submitMode != MODE_SET)) {
                // Changed from another session meanwhile.
                finishRequest();
                setHasPassword(password.has_password);
                showMode(password.has_password ? MODE_OVERVIEW : MODE_SET);
                showAlert(getString(R.string.TelesrvLoginPasswordChangedElsewhere));
                return;
            }
            Utilities.globalQueue.postRunnable(() -> {
                TLRPC.InputCheckPasswordSRP check = buildCurrentCheck(password, submitMode == MODE_CHANGE || submitMode == MODE_REMOVE ? current : null);
                TL_account.passwordInputSettings settings = buildNewSettings(password, submitMode == MODE_REMOVE ? null : newPassword);
                AndroidUtilities.runOnUIThread(() -> {
                    if (check == null || settings == null) {
                        finishRequest();
                        AlertsCreator.showUpdateAppAlert(getParentActivity(), getString(R.string.UpdateAppAlert), true);
                        return;
                    }
                    sendUpdate(submitMode, check, settings);
                });
            });
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    // The proof of the current password, or inputCheckPasswordEmpty when setting the first password or
    // resetting a forgotten one. Runs off the UI thread (PBKDF2). Null: an algorithm this client cannot use.
    private static TLRPC.InputCheckPasswordSRP buildCurrentCheck(TL_account.Password password, String current) {
        if (current == null) {
            return new TLRPC.TL_inputCheckPasswordEmpty();
        }
        if (!(password.current_algo instanceof TLRPC.TL_passwordKdfAlgoSHA256SHA256PBKDF2HMACSHA512iter100000SHA256ModPow)) {
            return null;
        }
        TLRPC.TL_passwordKdfAlgoSHA256SHA256PBKDF2HMACSHA512iter100000SHA256ModPow algo = (TLRPC.TL_passwordKdfAlgoSHA256SHA256PBKDF2HMACSHA512iter100000SHA256ModPow) password.current_algo;
        byte[] x = SRPHelper.getX(AndroidUtilities.getStringBytes(current), algo);
        if (x == null) {
            return null;
        }
        return SRPHelper.startCheck(x, password.srp_id, password.srp_B, algo);
    }

    // new_settings for a new password (salt1 = server prefix + 32 random bytes, as for 2FA), or the
    // removal form (passwordKdfAlgoUnknown with an empty hash) when newPassword is null.
    private static TL_account.passwordInputSettings buildNewSettings(TL_account.Password password, String newPassword) {
        TL_account.passwordInputSettings settings = new TL_account.passwordInputSettings();
        settings.flags = 1;
        settings.hint = "";
        if (newPassword == null) {
            settings.new_algo = new TLRPC.TL_passwordKdfAlgoUnknown();
            settings.new_password_hash = new byte[0];
            return settings;
        }
        if (!(password.new_algo instanceof TLRPC.TL_passwordKdfAlgoSHA256SHA256PBKDF2HMACSHA512iter100000SHA256ModPow)) {
            return null;
        }
        TLRPC.TL_passwordKdfAlgoSHA256SHA256PBKDF2HMACSHA512iter100000SHA256ModPow serverAlgo = (TLRPC.TL_passwordKdfAlgoSHA256SHA256PBKDF2HMACSHA512iter100000SHA256ModPow) password.new_algo;
        TLRPC.TL_passwordKdfAlgoSHA256SHA256PBKDF2HMACSHA512iter100000SHA256ModPow algo = new TLRPC.TL_passwordKdfAlgoSHA256SHA256PBKDF2HMACSHA512iter100000SHA256ModPow();
        algo.salt1 = new byte[serverAlgo.salt1.length + 32];
        Utilities.random.nextBytes(algo.salt1);
        System.arraycopy(serverAlgo.salt1, 0, algo.salt1, 0, serverAlgo.salt1.length);
        algo.salt2 = serverAlgo.salt2;
        algo.g = serverAlgo.g;
        algo.p = serverAlgo.p;
        byte[] hash = SRPHelper.getVBytes(AndroidUtilities.getStringBytes(newPassword), algo);
        if (hash == null) {
            return null;
        }
        settings.new_algo = algo;
        settings.new_password_hash = hash;
        return settings;
    }

    private void sendUpdate(int submitMode, TLRPC.InputCheckPasswordSRP check, TL_account.passwordInputSettings settings) {
        TL_telesrv.updateLoginPassword req = new TL_telesrv.updateLoginPassword();
        req.current = check;
        req.new_settings = settings;
        getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            finishRequest();
            if (response instanceof TLRPC.TL_boolTrue) {
                onUpdated(submitMode);
            } else if (error != null) {
                onUpdateError(submitMode, error);
            } else {
                showAlert(getString(R.string.TelesrvLoginPasswordFailed));
            }
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private void onUpdated(int submitMode) {
        boolean removed = submitMode == MODE_REMOVE;
        setHasPassword(!removed);
        showMode(removed ? MODE_SET : MODE_OVERVIEW);
        int text;
        switch (submitMode) {
            case MODE_SET:
                text = R.string.TelesrvLoginPasswordSetDone;
                break;
            case MODE_REMOVE:
                text = R.string.TelesrvLoginPasswordRemoveDone;
                break;
            case MODE_RESET:
                text = R.string.TelesrvLoginPasswordResetDone;
                break;
            default:
                text = R.string.TelesrvLoginPasswordChangeDone;
                break;
        }
        BulletinFactory.of(this).createSuccessBulletin(getString(text)).show();
    }

    private void onUpdateError(int submitMode, TLRPC.TL_error error) {
        if (handleCommonError(error)) {
            return;
        }
        String text = error.text != null ? error.text : "";
        if (text.equals("PASSWORD_HASH_INVALID")) {
            if (submitMode == MODE_RESET) {
                // Empty current refused: the reset window is off, has passed, or cannot be read.
                boolean codeLoginDisabled = TelesrvLoginConfig.getInstance(currentAccount).isCodeLoginDisabled();
                showAlert(getString(codeLoginDisabled ? R.string.TelesrvLoginPasswordResetUnavailableNoCode : R.string.TelesrvLoginPasswordResetUnavailable));
            } else if (currentField != null) {
                currentField.setText("");
                onFieldError(currentOutline, currentField);
                showAlert(getString(R.string.CheckPasswordWrong));
            } else {
                // Setting a first password never sends a proof: the server's state moved under us.
                showAlert(getString(R.string.TelesrvLoginPasswordChangedElsewhere));
                hasPassword = null;
                showMode(MODE_LOADING);
                loadState();
            }
        } else if (text.equals("SRP_PASSWORD_CHANGED")) {
            showAlert(getString(R.string.TelesrvLoginPasswordChangedElsewhere));
            hasPassword = null;
            showMode(MODE_LOADING);
            loadState();
        } else if (text.equals("SRP_ID_INVALID")) {
            // The challenge expired or the connection's key rotated: a new attempt gets a new one.
            showAlert(getString(R.string.TelesrvLoginPasswordRetry));
        } else if (text.equals("NEW_SALT_INVALID") || text.equals("NEW_SETTINGS_INVALID")) {
            FileLog.e("telesrv updateLoginPassword rejected: " + text);
            showAlert(getString(R.string.TelesrvLoginPasswordFailed));
        } else {
            showError(error);
        }
    }

    // Errors every telesrv login-password method can answer. True when handled.
    private boolean handleCommonError(TLRPC.TL_error error) {
        if (error == null || error.text == null) {
            return false;
        }
        if (error.text.equals("PASSWORD_LOGIN_DISABLED")) {
            TelesrvLoginConfig config = TelesrvLoginConfig.getInstance(currentAccount);
            config.markPasswordLoginDisabled();
            config.refresh(null);
            finishFragment();
            BaseFragment last = getParentLayout() != null ? getParentLayout().getLastFragment() : null;
            if (last != null && last.getParentActivity() != null) {
                last.showDialog(AlertsCreator.createSimpleAlert(last.getParentActivity(), getString(R.string.TelesrvLoginPassword), getString(R.string.TelesrvLoginPasswordDisabled)).create());
            }
            return true;
        }
        if (error.text.equals("FROZEN_METHOD_INVALID")) {
            AccountFrozenAlert.show(currentAccount);
            return true;
        }
        if (error.text.startsWith("FLOOD_WAIT")) {
            int time = Utilities.parseInt(error.text);
            String timeString = time < 60 ? LocaleController.formatPluralString("Seconds", time) : LocaleController.formatPluralString("Minutes", time / 60);
            showAlert(LocaleController.formatString(R.string.FloodWaitTime, timeString));
            return true;
        }
        return false;
    }

    private void showError(TLRPC.TL_error error) {
        if (error == null || error.code == -1000) {
            return;
        }
        showAlert(getString(R.string.TelesrvLoginPasswordFailed) + (error.text != null ? "\n\n" + error.text : ""));
    }

    private void finishRequest() {
        requestInProgress = false;
        hideProgress();
    }

    // endregion
}
