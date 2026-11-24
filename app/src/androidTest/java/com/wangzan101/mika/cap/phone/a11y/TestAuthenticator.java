package com.wangzan101.mika.cap.phone.a11y;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.content.Context;
import android.os.Bundle;

/**
 * Test account authenticator: makes the test package show up in the system's list of account authenticators, used to verify the criterion that "an account authenticator's sign-in page stays on the home screen".
 * It supports no operations itself; it only registers the account type.
 */
public class TestAuthenticator extends AbstractAccountAuthenticator {
    TestAuthenticator(Context context) {
        super(context);
    }

    @Override
    public Bundle editProperties(AccountAuthenticatorResponse response, String accountType) {
        return unsupported();
    }

    @Override
    public Bundle addAccount(AccountAuthenticatorResponse response, String accountType, String authTokenType,
                             String[] requiredFeatures, Bundle options) {
        return unsupported();
    }

    @Override
    public Bundle confirmCredentials(AccountAuthenticatorResponse response, Account account, Bundle options) {
        return unsupported();
    }

    @Override
    public Bundle getAuthToken(AccountAuthenticatorResponse response, Account account, String authTokenType,
                               Bundle options) {
        return unsupported();
    }

    @Override
    public String getAuthTokenLabel(String authTokenType) {
        return null;
    }

    @Override
    public Bundle updateCredentials(AccountAuthenticatorResponse response, Account account, String authTokenType,
                                    Bundle options) {
        return unsupported();
    }

    @Override
    public Bundle hasFeatures(AccountAuthenticatorResponse response, Account account, String[] features) {
        return unsupported();
    }

    private Bundle unsupported() {
        Bundle b = new Bundle();
        b.putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION);
        b.putString(AccountManager.KEY_ERROR_MESSAGE, "unsupported");
        return b;
    }
}
