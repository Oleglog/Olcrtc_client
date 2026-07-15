#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "hev-socks5-tunnel.h"

JNIEXPORT jint JNICALL
Java_io_github_oleglog_olcrtc_client_vpn_HevTunnel_nativeRun(
    JNIEnv *env, jobject self, jbyteArray config, jint tun_fd)
{
    jsize config_len;
    jbyte *config_bytes;
    unsigned char *config_copy;
    int result;

    (void)self;
    if (config == NULL || tun_fd < 0)
        return -1;

    config_len = (*env)->GetArrayLength(env, config);
    if (config_len <= 0)
        return -1;

    config_copy = malloc((size_t)config_len);
    if (config_copy == NULL)
        return -1;

    config_bytes = (*env)->GetByteArrayElements(env, config, NULL);
    if (config_bytes == NULL) {
        free(config_copy);
        return -1;
    }
    memcpy(config_copy, config_bytes, (size_t)config_len);
    (*env)->ReleaseByteArrayElements(env, config, config_bytes, JNI_ABORT);

    result = hev_socks5_tunnel_main_from_str(config_copy,
                                              (unsigned int)config_len,
                                              tun_fd);
    free(config_copy);
    return result;
}

JNIEXPORT void JNICALL
Java_io_github_oleglog_olcrtc_client_vpn_HevTunnel_nativeStop(
    JNIEnv *env, jobject self)
{
    (void)env;
    (void)self;
    hev_socks5_tunnel_quit();
}

JNIEXPORT jlongArray JNICALL
Java_io_github_oleglog_olcrtc_client_vpn_HevTunnel_nativeStats(
    JNIEnv *env, jobject self)
{
    size_t tx_packets;
    size_t tx_bytes;
    size_t rx_packets;
    size_t rx_bytes;
    jlong values[4];
    jlongArray result;

    (void)self;
    hev_socks5_tunnel_stats(&tx_packets, &tx_bytes, &rx_packets, &rx_bytes);
    values[0] = (jlong)tx_packets;
    values[1] = (jlong)tx_bytes;
    values[2] = (jlong)rx_packets;
    values[3] = (jlong)rx_bytes;

    result = (*env)->NewLongArray(env, 4);
    if (result != NULL)
        (*env)->SetLongArrayRegion(env, result, 0, 4, values);
    return result;
}
