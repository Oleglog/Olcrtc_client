package io.github.oleglog.olcrtc.client.vpn;

interface IVpnStateCallback {
    void onStateChanged(int state, String error);
}
