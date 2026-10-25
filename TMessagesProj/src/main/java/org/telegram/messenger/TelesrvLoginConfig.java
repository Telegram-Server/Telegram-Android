package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

// Login-related switches a telesrv server advertises in help.getAppConfig, which is callable
// before sign-in. Absent keys (official servers, older telesrv builds) mean the stock
// code login. See TgSever docs/password-login.md, code-login-switch.md, sign-up-switch.md.
public class TelesrvLoginConfig {

    private static final String KEY_FEATURES = "telesrv_features";
    private static final String FEATURE_PASSWORD_LOGIN = "password_login";
    private static final String KEY_CODE_LOGIN_DISABLED = "telesrv_code_login_disabled";
    private static final String KEY_SIGN_UP_DISABLED = "telesrv_sign_up_disabled";

    private static final TelesrvLoginConfig[] Instance = new TelesrvLoginConfig[UserConfig.MAX_ACCOUNT_COUNT];

    public static TelesrvLoginConfig getInstance(int account) {
        TelesrvLoginConfig localInstance = Instance[account];
        if (localInstance == null) {
            synchronized (TelesrvLoginConfig.class) {
                localInstance = Instance[account];
                if (localInstance == null) {
                    Instance[account] = localInstance = new TelesrvLoginConfig(account);
                }
            }
        }
        return localInstance;
    }

    private final int currentAccount;
    private boolean passwordLogin;
    private boolean codeLoginDisabled;
    private boolean signUpDisabled;

    private boolean loading;
    private boolean refreshed;
    private final ArrayList<Runnable> pendingCallbacks = new ArrayList<>();

    private TelesrvLoginConfig(int account) {
        currentAccount = account;
        SharedPreferences preferences = getPreferences();
        passwordLogin = preferences.getBoolean("passwordLogin", false);
        codeLoginDisabled = preferences.getBoolean("codeLoginDisabled", false);
        signUpDisabled = preferences.getBoolean("signUpDisabled", false);
    }

    private SharedPreferences getPreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("telesrv_login" + currentAccount, Context.MODE_PRIVATE);
    }

    public boolean isPasswordLoginEnabled() {
        return passwordLogin;
    }

    // The server only turns code login off while password login is on.
    public boolean isCodeLoginDisabled() {
        return passwordLogin && codeLoginDisabled;
    }

    public boolean isSignUpDisabled() {
        return signUpDisabled;
    }

    // True once a refresh in this process has finished, successfully or not.
    public boolean isRefreshed() {
        return refreshed;
    }

    // Fetches the app config without a signed-in session. On failure the cached values stay.
    // onDone runs on the UI thread.
    public void refresh(Runnable onDone) {
        AndroidUtilities.runOnUIThread(() -> {
            if (onDone != null) {
                pendingCallbacks.add(onDone);
            }
            if (loading) {
                return;
            }
            loading = true;
            TLRPC.TL_help_getAppConfig req = new TLRPC.TL_help_getAppConfig();
            req.hash = 0;
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TLRPC.TL_help_appConfig) {
                    apply(((TLRPC.TL_help_appConfig) response).config);
                } else if (error != null) {
                    FileLog.e("telesrv login config error " + error.code + " " + error.text);
                }
                loading = false;
                refreshed = true;
                ArrayList<Runnable> callbacks = new ArrayList<>(pendingCallbacks);
                pendingCallbacks.clear();
                for (int a = 0; a < callbacks.size(); a++) {
                    callbacks.get(a).run();
                }
            }), ConnectionsManager.RequestFlagWithoutLogin | ConnectionsManager.RequestFlagFailOnServerErrors);
        });
    }

    private void apply(TLRPC.JSONValue config) {
        boolean newPasswordLogin = false;
        boolean newCodeLoginDisabled = false;
        boolean newSignUpDisabled = false;
        if (config instanceof TLRPC.TL_jsonObject) {
            ArrayList<TLRPC.TL_jsonObjectValue> values = ((TLRPC.TL_jsonObject) config).value;
            for (int a = 0; a < values.size(); a++) {
                TLRPC.TL_jsonObjectValue value = values.get(a);
                if (KEY_FEATURES.equals(value.key)) {
                    if (value.value instanceof TLRPC.TL_jsonArray) {
                        ArrayList<TLRPC.JSONValue> features = ((TLRPC.TL_jsonArray) value.value).value;
                        for (int b = 0; b < features.size(); b++) {
                            TLRPC.JSONValue feature = features.get(b);
                            if (feature instanceof TLRPC.TL_jsonString && FEATURE_PASSWORD_LOGIN.equals(((TLRPC.TL_jsonString) feature).value)) {
                                newPasswordLogin = true;
                            }
                        }
                    }
                } else if (KEY_CODE_LOGIN_DISABLED.equals(value.key)) {
                    newCodeLoginDisabled = value.value instanceof TLRPC.TL_jsonBool && ((TLRPC.TL_jsonBool) value.value).value;
                } else if (KEY_SIGN_UP_DISABLED.equals(value.key)) {
                    newSignUpDisabled = value.value instanceof TLRPC.TL_jsonBool && ((TLRPC.TL_jsonBool) value.value).value;
                }
            }
        }
        passwordLogin = newPasswordLogin;
        codeLoginDisabled = newCodeLoginDisabled;
        signUpDisabled = newSignUpDisabled;
        getPreferences().edit()
                .putBoolean("passwordLogin", passwordLogin)
                .putBoolean("codeLoginDisabled", codeLoginDisabled)
                .putBoolean("signUpDisabled", signUpDisabled)
                .apply();
    }

    // Called when the server refuses a method because a switch changed since the last refresh.
    public void markPasswordLoginDisabled() {
        passwordLogin = false;
        codeLoginDisabled = false;
        getPreferences().edit().putBoolean("passwordLogin", false).putBoolean("codeLoginDisabled", false).apply();
    }
}
