#include <jni.h>
#include <android/log.h>
#include <arpa/inet.h>
#include <errno.h>
#include <netdb.h>
#include <netinet/ip_icmp.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#define LOG_TAG "iodine-probe"

static uint16_t
icmp_checksum(const void *data, size_t len)
{
    const uint8_t *bytes = (const uint8_t *) data;
    uint32_t sum = 0;
    size_t i;

    for (i = 0; i + 1 < len; i += 2)
        sum += ((uint32_t) bytes[i] << 8) | bytes[i + 1];
    if (i < len)
        sum += ((uint32_t) bytes[i] << 8);

    while (sum >> 16)
        sum = (sum & 0xffffU) + (sum >> 16);
    return (uint16_t) (~sum & 0xffffU);
}

static void
append_line(char *buf, size_t buflen, const char *fmt, ...)
{
    size_t used;
    va_list ap;

    if (buflen == 0)
        return;

    used = strlen(buf);
    if (used >= buflen - 1)
        return;

    va_start(ap, fmt);
    vsnprintf(buf + used, buflen - used, fmt, ap);
    va_end(ap);
}

JNIEXPORT jstring JNICALL
Java_se_kryo_iodine_probe_ProbeActivity_nativePingIcmp(JNIEnv *env, jobject thiz,
                                                       jstring target, jint count, jint timeout_ms)
{
    const char *target_utf = NULL;
    struct addrinfo hints;
    struct addrinfo *result = NULL;
    int sock = -1;
    int rc;
    char out[4096];
    struct timeval timeout;
    uint16_t ident;
    int seq;
    (void) thiz;

    out[0] = '\0';

    target_utf = (*env)->GetStringUTFChars(env, target, NULL);
    if (target_utf == NULL)
        return NULL;

    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_INET;
    hints.ai_socktype = 0;
    hints.ai_protocol = 0;

    rc = getaddrinfo(target_utf, NULL, &hints, &result);
    if (rc != 0) {
        append_line(out, sizeof(out), "native ping resolve failed: %s\n", gai_strerror(rc));
        goto done;
    }

    sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_ICMP);
    if (sock < 0) {
        append_line(out, sizeof(out), "native ping socket failed: %s\n", strerror(errno));
        goto done;
    }

    timeout.tv_sec = timeout_ms / 1000;
    timeout.tv_usec = (timeout_ms % 1000) * 1000;
    if (setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) != 0) {
        append_line(out, sizeof(out), "native ping setsockopt failed: %s\n", strerror(errno));
        goto done;
    }

    ident = (uint16_t) (((unsigned int) getpid() ^ (unsigned int) time(NULL)) & 0xffffU);
    append_line(out, sizeof(out), "native ping start: target=%s count=%d timeout=%dms\n",
                target_utf, (int) count, (int) timeout_ms);

    for (seq = 1; seq <= count; seq++) {
        struct {
            struct icmphdr hdr;
            unsigned char payload[32];
        } packet;
        struct timespec start_ts;
        struct timespec end_ts;
        ssize_t sent;
        int matched = 0;

        memset(&packet, 0, sizeof(packet));
        packet.hdr.type = ICMP_ECHO;
        packet.hdr.code = 0;
        packet.hdr.un.echo.id = htons(ident);
        packet.hdr.un.echo.sequence = htons((uint16_t) seq);
        for (size_t i = 0; i < sizeof(packet.payload); i++)
            packet.payload[i] = (unsigned char) ('A' + (i % 26));
        packet.hdr.checksum = icmp_checksum(&packet, sizeof(packet));

        clock_gettime(CLOCK_MONOTONIC, &start_ts);
        sent = sendto(sock, &packet, sizeof(packet), 0, result->ai_addr, result->ai_addrlen);
        if (sent < 0) {
            append_line(out, sizeof(out), "native ping seq=%d send failed: %s\n", seq, strerror(errno));
            continue;
        }

        for (;;) {
            unsigned char reply[512];
            struct sockaddr_in from;
            socklen_t fromlen = sizeof(from);
            ssize_t received = recvfrom(sock, reply, sizeof(reply), 0,
                                        (struct sockaddr *) &from, &fromlen);
            if (received < 0) {
                append_line(out, sizeof(out), "native ping seq=%d timeout/error: %s\n", seq, strerror(errno));
                break;
            }

            if ((size_t) received < sizeof(struct icmphdr)) {
                append_line(out, sizeof(out), "native ping seq=%d short reply=%ld\n", seq, (long) received);
                continue;
            }

            struct icmphdr *icmp = (struct icmphdr *) reply;
            if (icmp->type != ICMP_ECHOREPLY)
                continue;
            if (ntohs(icmp->un.echo.id) != ident)
                continue;
            if (ntohs(icmp->un.echo.sequence) != (uint16_t) seq)
                continue;

            clock_gettime(CLOCK_MONOTONIC, &end_ts);
            long rtt_ms = (end_ts.tv_sec - start_ts.tv_sec) * 1000L +
                          (end_ts.tv_nsec - start_ts.tv_nsec) / 1000000L;
            append_line(out, sizeof(out),
                        "native ping reply: %s seq=%d bytes=%ld rtt=%ldms type=%u code=%u\n",
                        inet_ntoa(from.sin_addr), seq, (long) received, rtt_ms,
                        (unsigned int) icmp->type, (unsigned int) icmp->code);
            matched = 1;
            break;
        }

        if (!matched) {
            __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                                "native ping seq=%d got no matching reply", seq);
        }
    }

done:
    if (sock >= 0)
        close(sock);
    if (result != NULL)
        freeaddrinfo(result);
    if (target_utf != NULL)
        (*env)->ReleaseStringUTFChars(env, target, target_utf);
    return (*env)->NewStringUTF(env, out);
}
