package com.wangzan101.mika.cap.phone.a11y;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Hands the test account authenticator to the system (see {@link TestAuthenticator}). */
public class TestAuthenticatorService extends Service {
    private TestAuthenticator authenticator;

    @Override
    public void onCreate() {
        super.onCreate();
        authenticator = new TestAuthenticator(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return authenticator.getIBinder();
    }
}
