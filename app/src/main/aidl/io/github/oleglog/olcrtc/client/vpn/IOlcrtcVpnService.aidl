package io.github.oleglog.olcrtc.client.vpn;

import io.github.oleglog.olcrtc.client.vpn.IVpnStateCallback;

interface IOlcrtcVpnService {
    void start(long profileId);
    void startSubscriptionProfile(String profileId);
    void stop();
    void reconnect();
    int getState();
    void registerCallback(IVpnStateCallback callback);
    void unregisterCallback(IVpnStateCallback callback);
}
