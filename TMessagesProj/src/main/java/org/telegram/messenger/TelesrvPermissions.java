package org.telegram.messenger;

import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;
import java.util.Arrays;

// telesrv extension: staff permissions from the telesrv_permissions app config key
// (TgSever internal/app/permissions, Telegram Desktop data/components/telesrv_permissions).
// The key is present only while the account is restricted; without it nothing is forbidden.
public class TelesrvPermissions {

    public static final int SEND = 0,
            DELETE = 1,
            EDIT = 2,
            CLEAR_HISTORY = 3,
            CREATE_GROUP = 4,
            CREATE_CHANNEL = 5,
            JOIN_GROUP = 6,
            JOIN_CHANNEL = 7,
            LEAVE_GROUP = 8,
            LEAVE_CHANNEL = 9,
            ADD_CONTACT = 10;

    private static final String[] KEYS = {
            "send", "delete", "edit", "clear_history",
            "create_group", "create_channel",
            "join_group", "join_channel",
            "leave_group", "leave_channel",
            "add_contact"
    };

    private static final String CONFIG_KEY = "telesrv_permissions";
    private static final String CACHE_KEY = "telesrvPermissions";
    private static final int CACHE_VERSION = 1;
    private static final String ERROR_PREFIX = "TELESRV_PERMISSION_";
    private static final long REFRESH_THROTTLE_MS = 30_000;
    private static final long TOAST_THROTTLE_MS = 2_000;

    private static final TelesrvPermissions[] Instance = new TelesrvPermissions[UserConfig.MAX_ACCOUNT_COUNT];

    public static TelesrvPermissions getInstance(int account) {
        TelesrvPermissions localInstance = Instance[account];
        if (localInstance == null) {
            synchronized (TelesrvPermissions.class) {
                localInstance = Instance[account];
                if (localInstance == null) {
                    Instance[account] = localInstance = new TelesrvPermissions(account);
                }
            }
        }
        return localInstance;
    }

    public static boolean isPermissionError(TLRPC.TL_error error) {
        return error != null && error.text != null && error.text.startsWith(ERROR_PREFIX);
    }

    public static String deniedText() {
        return LocaleController.getString(R.string.TelesrvPermissionDenied);
    }

    private final int currentAccount;
    private volatile int forbidden;
    private volatile boolean whitelistEnabled;
    private volatile long[] whitelist = new long[0];

    private long lastRefresh;
    private long lastToast;

    private TelesrvPermissions(int account) {
        currentAccount = account;
        deserialize(MessagesController.getMainSettings(account).getString(CACHE_KEY, null));
    }

    public boolean allows(int action) {
        return (forbidden & (1 << action)) == 0;
    }

    // Saved Messages are never restricted; the whitelist applies to private and secret chats only.
    public boolean canSendTo(long dialogId) {
        return sendErrorText(dialogId) == null;
    }

    public String sendErrorText(long dialogId) {
        if (forbidden == 0 && !whitelistEnabled) {
            return null;
        }
        long userId = dialogUserId(dialogId);
        if (userId != 0 && userId == UserConfig.getInstance(currentAccount).getClientUserId()) {
            return null;
        }
        if (!allows(SEND)) {
            return LocaleController.getString(R.string.TelesrvSendRestricted);
        }
        if (whitelistEnabled && userId != 0 && Arrays.binarySearch(whitelist, userId) < 0) {
            return LocaleController.getString(R.string.TelesrvSendWhitelistRestricted);
        }
        if (whitelistEnabled && DialogObject.isEncryptedDialog(dialogId) && userId == 0) {
            return LocaleController.getString(R.string.TelesrvSendWhitelistRestricted);
        }
        return null;
    }

    public boolean inSendWhitelist(long userId) {
        return !whitelistEnabled || userId == UserConfig.getInstance(currentAccount).getClientUserId() || Arrays.binarySearch(whitelist, userId) >= 0;
    }

    // Only private chats and Saved Messages; clearing a group or channel is never refused.
    public boolean canClearHistory(long dialogId) {
        return !DialogObject.isUserDialog(dialogId) && !DialogObject.isEncryptedDialog(dialogId) || allows(CLEAR_HISTORY);
    }

    // Deleting a private chat clears its history; deleting a group or channel leaves it.
    public boolean canDeleteDialog(long dialogId, TLRPC.Chat chat) {
        if (DialogObject.isUserDialog(dialogId) || DialogObject.isEncryptedDialog(dialogId)) {
            return allows(CLEAR_HISTORY);
        }
        return canLeave(chat);
    }

    public boolean canJoin(TLRPC.Chat chat) {
        return chat == null || canJoinByType(ChatObject.isChannelAndNotMegaGroup(chat));
    }

    public boolean canJoinByType(boolean broadcast) {
        return allows(broadcast ? JOIN_CHANNEL : JOIN_GROUP);
    }

    public boolean canLeave(TLRPC.Chat chat) {
        return chat == null || allows(ChatObject.isChannelAndNotMegaGroup(chat) ? LEAVE_CHANNEL : LEAVE_GROUP);
    }

    public boolean canCreate(boolean broadcast) {
        return allows(broadcast ? CREATE_CHANNEL : CREATE_GROUP);
    }

    private long dialogUserId(long dialogId) {
        if (DialogObject.isEncryptedDialog(dialogId)) {
            TLRPC.EncryptedChat encryptedChat = MessagesController.getInstance(currentAccount).getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
            return encryptedChat != null ? encryptedChat.user_id : 0;
        }
        return DialogObject.isUserDialog(dialogId) ? dialogId : 0;
    }

    // Called from MessagesController.applyAppConfig with the whole config object.
    public void applyAppConfig(TLRPC.TL_jsonObject config) {
        int newForbidden = 0;
        boolean newWhitelistEnabled = false;
        ArrayList<Long> ids = new ArrayList<>();
        TLRPC.JSONValue value = null;
        for (int a = 0; a < config.value.size(); a++) {
            if (CONFIG_KEY.equals(config.value.get(a).key)) {
                value = config.value.get(a).value;
                break;
            }
        }
        if (value instanceof TLRPC.TL_jsonObject) {
            ArrayList<TLRPC.TL_jsonObjectValue> entries = ((TLRPC.TL_jsonObject) value).value;
            for (int a = 0; a < entries.size(); a++) {
                TLRPC.TL_jsonObjectValue entry = entries.get(a);
                if ("send_whitelist".equals(entry.key)) {
                    if (!(entry.value instanceof TLRPC.TL_jsonObject)) {
                        continue;
                    }
                    ArrayList<TLRPC.TL_jsonObjectValue> fields = ((TLRPC.TL_jsonObject) entry.value).value;
                    for (int b = 0; b < fields.size(); b++) {
                        TLRPC.TL_jsonObjectValue field = fields.get(b);
                        if ("enabled".equals(field.key) && field.value instanceof TLRPC.TL_jsonBool) {
                            newWhitelistEnabled = ((TLRPC.TL_jsonBool) field.value).value;
                        } else if ("user_ids".equals(field.key) && field.value instanceof TLRPC.TL_jsonArray) {
                            ArrayList<TLRPC.JSONValue> array = ((TLRPC.TL_jsonArray) field.value).value;
                            for (int c = 0; c < array.size(); c++) {
                                if (array.get(c) instanceof TLRPC.TL_jsonNumber) {
                                    ids.add(Math.round(((TLRPC.TL_jsonNumber) array.get(c)).value));
                                }
                            }
                        }
                    }
                } else if (entry.value instanceof TLRPC.TL_jsonBool && !((TLRPC.TL_jsonBool) entry.value).value) {
                    int action = Arrays.asList(KEYS).indexOf(entry.key);
                    if (action >= 0) {
                        newForbidden |= 1 << action;
                    }
                }
            }
        }
        long[] newWhitelist = new long[ids.size()];
        for (int a = 0; a < newWhitelist.length; a++) {
            newWhitelist[a] = ids.get(a);
        }
        Arrays.sort(newWhitelist);
        apply(newForbidden, newWhitelistEnabled, newWhitelist);
    }

    private void apply(int newForbidden, boolean newWhitelistEnabled, long[] newWhitelist) {
        if (forbidden == newForbidden && whitelistEnabled == newWhitelistEnabled && Arrays.equals(whitelist, newWhitelist)) {
            return;
        }
        forbidden = newForbidden;
        whitelistEnabled = newWhitelistEnabled;
        whitelist = newWhitelist;
        SharedPreferences.Editor editor = MessagesController.getMainSettings(currentAccount).edit();
        if (forbidden == 0 && !whitelistEnabled && whitelist.length == 0) {
            editor.remove(CACHE_KEY);
        } else {
            editor.putString(CACHE_KEY, serialize());
        }
        editor.apply();
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.telesrvPermissionsChanged));
    }

    private String serialize() {
        StringBuilder ids = new StringBuilder();
        for (int a = 0; a < whitelist.length; a++) {
            if (a > 0) {
                ids.append(',');
            }
            ids.append(whitelist[a]);
        }
        return CACHE_VERSION + ";" + forbidden + ";" + (whitelistEnabled ? 1 : 0) + ";" + ids;
    }

    private void deserialize(String data) {
        if (TextUtils.isEmpty(data)) {
            return;
        }
        String[] parts = data.split(";", -1);
        if (parts.length != 4 || Utilities.parseInt(parts[0]) != CACHE_VERSION) {
            return;
        }
        forbidden = Utilities.parseInt(parts[1]);
        whitelistEnabled = "1".equals(parts[2]);
        ArrayList<Long> ids = new ArrayList<>();
        for (String id : parts[3].split(",")) {
            if (!id.isEmpty()) {
                ids.add(Utilities.parseLong(id));
            }
        }
        long[] result = new long[ids.size()];
        for (int a = 0; a < result.length; a++) {
            result[a] = ids.get(a);
        }
        Arrays.sort(result);
        whitelist = result;
    }

    // The server refused an action: the cached permissions are stale. Refresh them (throttled)
    // and tell the user once, whatever request it was. Any thread.
    public void onPermissionError(TLRPC.TL_error error) {
        AndroidUtilities.runOnUIThread(() -> {
            long now = SystemClock.elapsedRealtime();
            if (lastRefresh == 0 || now - lastRefresh > REFRESH_THROTTLE_MS) {
                lastRefresh = now;
                MessagesController.getInstance(currentAccount).loadAppConfig(true);
            }
            if (lastToast == 0 || now - lastToast > TOAST_THROTTLE_MS) {
                lastToast = now;
                String text;
                if ("TELESRV_PERMISSION_SEND".equals(error.text)) {
                    text = LocaleController.getString(R.string.TelesrvSendRestricted);
                } else if ("TELESRV_PERMISSION_SEND_WHITELIST".equals(error.text)) {
                    text = LocaleController.getString(R.string.TelesrvSendWhitelistRestricted);
                } else if (error.text.startsWith("TELESRV_PERMISSION_JOIN")) {
                    text = LocaleController.getString(R.string.TelesrvJoinRestricted);
                } else {
                    text = deniedText();
                }
                BulletinFactory.global().createErrorBulletin(text).show();
            }
        });
    }

    // Shows the generic refusal for an action stopped on the client before any request.
    public static void showDenied(String text) {
        AndroidUtilities.runOnUIThread(() -> BulletinFactory.global().createErrorBulletin(text != null ? text : deniedText()).show());
    }
}
