package org.telegram.tgnet.tl;

import org.telegram.tgnet.InputSerializedData;
import org.telegram.tgnet.OutputSerializedData;
import org.telegram.tgnet.TLMethod;
import org.telegram.tgnet.TLRPC;

// telesrv-only RPCs (phone number + login password). Mirrors _schema/telesrv.tl of
// github.com/Telegram-Server/td; never sent to official servers. Re-check the wire IDs
// against telesrv.tl whenever the server's td dependency moves.
public class TL_telesrv {

    // telesrv.getLoginPassword#e35bc783 flags:# phone_number:string = account.Password;
    public static class getLoginPassword extends TLMethod<TL_account.Password> {
        public static final int constructor = 0xe35bc783;

        public int flags;
        public String phone_number;

        @Override
        public TL_account.Password deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TL_account.Password.TLdeserialize(stream, constructor, exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeString(phone_number);
        }
    }

    // telesrv.signInWithPassword#b57ee87d flags:# phone_number:string password:InputCheckPasswordSRP = auth.Authorization;
    public static class signInWithPassword extends TLMethod<TLRPC.auth_Authorization> {
        public static final int constructor = 0xb57ee87d;

        public int flags;
        public String phone_number;
        public TLRPC.InputCheckPasswordSRP password;

        @Override
        public TLRPC.auth_Authorization deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TLRPC.auth_Authorization.TLdeserialize(stream, constructor, exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeString(phone_number);
            password.serializeToStream(stream);
        }
    }

    // telesrv.signUpWithPassword#e57cf5b6 flags:# phone_number:string first_name:string last_name:string new_settings:account.PasswordInputSettings = auth.Authorization;
    public static class signUpWithPassword extends TLMethod<TLRPC.auth_Authorization> {
        public static final int constructor = 0xe57cf5b6;

        public int flags;
        public String phone_number;
        public String first_name;
        public String last_name;
        public TL_account.passwordInputSettings new_settings;

        @Override
        public TLRPC.auth_Authorization deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TLRPC.auth_Authorization.TLdeserialize(stream, constructor, exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeString(phone_number);
            stream.writeString(first_name);
            stream.writeString(last_name);
            new_settings.serializeToStream(stream);
        }
    }

    // telesrv.getMyLoginPassword#70c0eadb flags:# = account.Password;
    public static class getMyLoginPassword extends TLMethod<TL_account.Password> {
        public static final int constructor = 0x70c0eadb;

        public int flags;

        @Override
        public TL_account.Password deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TL_account.Password.TLdeserialize(stream, constructor, exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
        }
    }

    // telesrv.updateLoginPassword#917c32ca flags:# current:InputCheckPasswordSRP new_settings:account.PasswordInputSettings = Bool;
    public static class updateLoginPassword extends TLMethod<TLRPC.Bool> {
        public static final int constructor = 0x917c32ca;

        public int flags;
        public TLRPC.InputCheckPasswordSRP current;
        public TL_account.passwordInputSettings new_settings;

        @Override
        public TLRPC.Bool deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TLRPC.Bool.TLdeserialize(stream, constructor, exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            current.serializeToStream(stream);
            new_settings.serializeToStream(stream);
        }
    }
}
