//
// Created by Administrator on 2019/1/29/029.
//

#include "raop_rtp_mirror.h"

#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <assert.h>
#include <errno.h>

#include "raop.h"
#include "netutils.h"
#include "compat.h"
#include "logger.h"
#include "byteutils.h"
#include "mirror_buffer.h"
#include "stream.h"


struct h264codec_s {
    unsigned char compatibility;
    short lengthofPPS;
    short lengthofSPS;
    unsigned char level;
    unsigned char numberOfPPS;
    unsigned char* picture_parameter_set;
    unsigned char profile_high;
    unsigned char reserved3andSPS;
    unsigned char reserved6andNAL;
    unsigned char* sequence;
    unsigned char version;
};

struct raop_rtp_mirror_s {
    logger_t *logger;
    raop_callbacks_t callbacks;

    /* Buffer to handle all resends */
    mirror_buffer_t *buffer;

    raop_rtp_mirror_t *mirror;
    /* Remote address as sockaddr */
    struct sockaddr_storage remote_saddr;
    socklen_t remote_saddr_len;

    /* MUTEX LOCKED VARIABLES START */
    /* These variables only edited mutex locked */
    int running;
    int joined;

    int flush;
    thread_handle_t thread_mirror;
    thread_handle_t thread_time;
    mutex_handle_t run_mutex;

    mutex_handle_t time_mutex;
    cond_handle_t time_cond;
    /* MUTEX LOCKED VARIABLES END */
    int mirror_data_sock, mirror_time_sock, mirror_stream_sock;

    unsigned short mirror_data_lport;
    unsigned short mirror_timing_rport;
    unsigned short mirror_timing_lport;
};

static int
raop_rtp_parse_remote(raop_rtp_mirror_t *raop_rtp_mirror, const unsigned char *remote, int remotelen)
{
    char current[25];
    int family;
    int ret;
    assert(raop_rtp_mirror);
    if (remotelen == 4) {
        family = AF_INET;
    } else if (remotelen == 16) {
        family = AF_INET6;
    } else {
        return -1;
    }
    memset(current, 0, sizeof(current));
    sprintf(current, "%d.%d.%d.%d", remote[0], remote[1], remote[2], remote[3]);
    logger_log(raop_rtp_mirror->logger, LOGGER_DEBUG, "raop_rtp_parse_remote ip = %s", current);
    ret = netutils_parse_address(family, current,
                                 &raop_rtp_mirror->remote_saddr,
                                 sizeof(raop_rtp_mirror->remote_saddr));
    if (ret < 0) {
        return -1;
    }
    raop_rtp_mirror->remote_saddr_len = ret;
    return 0;
}

#define NO_FLUSH (-42)
raop_rtp_mirror_t *raop_rtp_mirror_init(logger_t *logger, raop_callbacks_t *callbacks, const unsigned char *remote, int remotelen,
                                        const unsigned char *aeskey, const unsigned char *ecdh_secret, unsigned short timing_rport)
{
    raop_rtp_mirror_t *raop_rtp_mirror;

    assert(logger);
    assert(callbacks);

    raop_rtp_mirror = calloc(1, sizeof(raop_rtp_mirror_t));
    if (!raop_rtp_mirror) {
        return NULL;
    }
    raop_rtp_mirror->logger = logger;
    raop_rtp_mirror->mirror_timing_rport = timing_rport;

    memcpy(&raop_rtp_mirror->callbacks, callbacks, sizeof(raop_callbacks_t));
    raop_rtp_mirror->buffer = mirror_buffer_init(logger, aeskey, ecdh_secret);
    if (!raop_rtp_mirror->buffer) {
        free(raop_rtp_mirror);
        return NULL;
    }
    if (raop_rtp_parse_remote(raop_rtp_mirror, remote, remotelen) < 0) {
        free(raop_rtp_mirror);
        return NULL;
    }
    raop_rtp_mirror->running = 0;
    raop_rtp_mirror->joined = 1;
    raop_rtp_mirror->flush = NO_FLUSH;
    raop_rtp_mirror->mirror_data_sock = -1;
    raop_rtp_mirror->mirror_time_sock = -1;
    raop_rtp_mirror->mirror_stream_sock = -1;

    MUTEX_CREATE(raop_rtp_mirror->run_mutex);
    MUTEX_CREATE(raop_rtp_mirror->time_mutex);
    COND_CREATE(raop_rtp_mirror->time_cond);
    return raop_rtp_mirror;
}

void
raop_rtp_init_mirror_aes(raop_rtp_mirror_t *raop_rtp_mirror, uint64_t streamConnectionID)
{
    mirror_buffer_init_aes(raop_rtp_mirror->buffer, streamConnectionID);
}

/**
 * ntp
 */
static THREAD_RETVAL
raop_rtp_mirror_thread_time(void *arg)
{
    raop_rtp_mirror_t *raop_rtp_mirror = arg;
    assert(raop_rtp_mirror);
    struct sockaddr_storage saddr;
    socklen_t saddrlen;
    unsigned char packet[128];
    int packetlen;
    int first = 0;
    unsigned char time[48]={35,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0};
    uint64_t base = now_us();
    uint64_t rec_pts = 0;
    while (1) {
        MUTEX_LOCK(raop_rtp_mirror->run_mutex);
        if (!raop_rtp_mirror->running) {
            MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
            break;
        }
        MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
        uint64_t send_time = now_us() - base + rec_pts;

        byteutils_put_timeStamp(time, 40, send_time);
        logger_log(raop_rtp_mirror->logger, LOGGER_DEBUG, "raop_rtp_mirror_thread_time send time 48 bytes, port = %d", raop_rtp_mirror->mirror_timing_rport);
        struct sockaddr_in *addr = (struct sockaddr_in *)&raop_rtp_mirror->remote_saddr;
        addr->sin_port = htons(raop_rtp_mirror->mirror_timing_rport);
        int sendlen = sendto(raop_rtp_mirror->mirror_time_sock, (char *)time, sizeof(time), 0, (struct sockaddr *) &raop_rtp_mirror->remote_saddr, raop_rtp_mirror->remote_saddr_len);
        logger_log(raop_rtp_mirror->logger, LOGGER_DEBUG, "raop_rtp_mirror_thread_time sendlen = %d", sendlen);

        saddrlen = sizeof(saddr);
        packetlen = recvfrom(raop_rtp_mirror->mirror_time_sock, (char *)packet, sizeof(packet), 0,
                             (struct sockaddr *)&saddr, &saddrlen);
        logger_log(raop_rtp_mirror->logger, LOGGER_DEBUG, "raop_rtp_mirror_thread_time receive time packetlen = %d", packetlen);
        if (packetlen < 48) {
            MUTEX_LOCK(raop_rtp_mirror->run_mutex);
            int active = raop_rtp_mirror->running;
            MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
            if (!active) break;
            continue;
        }
        // 16-24 系统时钟最后一次被设定或更新的时间。
        uint64_t Reference_Timestamp = byteutils_read_timeStamp(packet, 16);
        // 24-32 NTP请求报文离开发送端时发送端的本地时间。  T1
        uint64_t Origin_Timestamp = byteutils_read_timeStamp(packet, 24);
        // 32-40 NTP请求报文到达接收端时接收端的本地时间。 T2
        uint64_t Receive_Timestamp = byteutils_read_timeStamp(packet, 32);
        // 40-48 Transmit Timestamp：应答报文离开应答者时应答者的本地时间。 T3
        uint64_t Transmit_Timestamp = byteutils_read_timeStamp(packet, 40);

        // FIXME: 先简单这样写吧
        rec_pts = Receive_Timestamp;

        if (first == 0) {
            first++;
        } else {
            struct timeval now;
            struct timespec outtime;
            MUTEX_LOCK(raop_rtp_mirror->time_mutex);
            gettimeofday(&now, NULL);
            outtime.tv_sec = now.tv_sec + 3;
            outtime.tv_nsec = now.tv_usec * 1000;
            int ret = pthread_cond_timedwait(&raop_rtp_mirror->time_cond, &raop_rtp_mirror->time_mutex, &outtime);
            MUTEX_UNLOCK(raop_rtp_mirror->time_mutex);
            //sleepms(3000);
        }
    }
    logger_log(raop_rtp_mirror->logger, LOGGER_INFO, "Exiting UDP raop_rtp_mirror_thread_time thread");
    return 0;
}
//#define DUMP_H264

#define MAX_MIRROR_PAYLOAD (8 * 1024 * 1024)

static int
recv_all(int fd, unsigned char *buffer, int length)
{
    int received = 0;
    while (received < length) {
        int count = recv(fd, buffer + received, length - received, 0);
        if (count == 0) return 0;
        if (count < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        received += count;
    }
    return received;
}

/**
 * 镜像
 */
static THREAD_RETVAL
raop_rtp_mirror_thread(void *arg)
{
    raop_rtp_mirror_t *raop_rtp_mirror = arg;
    int stream_fd = -1;
    unsigned char packet[128];
    uint64_t pts_base = 0;
    uint64_t pts = 0;
    assert(raop_rtp_mirror);

    while (1) {
        fd_set rfds;
        struct timeval tv;
        int nfds, ret;
        MUTEX_LOCK(raop_rtp_mirror->run_mutex);
        if (!raop_rtp_mirror->running) {
            MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
            break;
        }
        MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);

        tv.tv_sec = 0;
        tv.tv_usec = 5000;
        FD_ZERO(&rfds);
        if (stream_fd == -1) {
            FD_SET(raop_rtp_mirror->mirror_data_sock, &rfds);
            nfds = raop_rtp_mirror->mirror_data_sock + 1;
        } else {
            FD_SET(stream_fd, &rfds);
            nfds = stream_fd + 1;
        }
        ret = select(nfds, &rfds, NULL, NULL, &tv);
        if (ret == 0) continue;
        if (ret < 0) {
            if (errno != EINTR) logger_log(raop_rtp_mirror->logger, LOGGER_INFO, "Error in mirror select");
            break;
        }

        if (stream_fd == -1 && FD_ISSET(raop_rtp_mirror->mirror_data_sock, &rfds)) {
            struct sockaddr_storage saddr;
            socklen_t saddrlen = sizeof(saddr);
            stream_fd = accept(raop_rtp_mirror->mirror_data_sock,
                               (struct sockaddr *)&saddr, &saddrlen);
            if (stream_fd == -1) break;
            MUTEX_LOCK(raop_rtp_mirror->run_mutex);
            raop_rtp_mirror->mirror_stream_sock = stream_fd;
            MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
            if (raop_rtp_mirror->callbacks.session_changed) {
                raop_rtp_mirror->callbacks.session_changed(
                        raop_rtp_mirror->callbacks.cls, 1);
            }
            logger_log(raop_rtp_mirror->logger, LOGGER_INFO, "Accepted mirror stream");
            continue;
        }

        if (stream_fd == -1 || !FD_ISSET(stream_fd, &rfds)) continue;
        memset(packet, 0, sizeof(packet));
        if (recv_all(stream_fd, packet, 4) <= 0) break;
        if ((packet[0] == 'P' && packet[1] == 'O' && packet[2] == 'S' && packet[3] == 'T')
                || (packet[0] == 'G' && packet[1] == 'E' && packet[2] == 'T')) {
            logger_log(raop_rtp_mirror->logger, LOGGER_WARNING,
                       "Unexpected HTTP data on mirror socket");
            break;
        }
        if (recv_all(stream_fd, packet + 4, 124) <= 0) break;

        int payloadsize = byteutils_get_int(packet, 0);
        short payloadtype = (short)(byteutils_get_short(packet, 4) & 0xff);
        if (payloadsize < 0 || payloadsize > MAX_MIRROR_PAYLOAD) {
            logger_log(raop_rtp_mirror->logger, LOGGER_WARNING,
                       "Invalid mirror payload size %d", payloadsize);
            break;
        }

        unsigned char *payload_in = NULL;
        if (payloadsize > 0) {
            payload_in = malloc(payloadsize);
            if (!payload_in || recv_all(stream_fd, payload_in, payloadsize) <= 0) {
                free(payload_in);
                break;
            }
        }

        if (payloadtype == 0 && payloadsize >= 4) {
            uint64_t payloadntp = byteutils_get_long(packet, 8);
            uint64_t current_pts = ntptopts(payloadntp);
            if (pts_base == 0) pts_base = current_pts;
            else pts = current_pts >= pts_base ? current_pts - pts_base : 0;

            unsigned char *payload = malloc(payloadsize);
            if (!payload) {
                free(payload_in);
                break;
            }
            mirror_buffer_decrypt(raop_rtp_mirror->buffer, payload_in, payload, payloadsize);
            int offset = 0;
            int valid = 1;
            while (offset + 4 <= payloadsize) {
                unsigned int nalu_length = ((unsigned int)payload[offset] << 24)
                        | ((unsigned int)payload[offset + 1] << 16)
                        | ((unsigned int)payload[offset + 2] << 8)
                        | (unsigned int)payload[offset + 3];
                if (nalu_length == 0 || nalu_length > (unsigned int)(payloadsize - offset - 4)) {
                    valid = 0;
                    break;
                }
                payload[offset] = 0;
                payload[offset + 1] = 0;
                payload[offset + 2] = 0;
                payload[offset + 3] = 1;
                offset += (int)nalu_length + 4;
            }
            if (valid && offset == payloadsize) {
                h264_decode_struct h264_data;
                h264_data.data_len = payloadsize;
                h264_data.data = payload;
                h264_data.frame_type = 1;
                h264_data.pts = pts;
                raop_rtp_mirror->callbacks.video_process(
                        raop_rtp_mirror->callbacks.cls, &h264_data);
            } else {
                logger_log(raop_rtp_mirror->logger, LOGGER_WARNING,
                           "Invalid length-prefixed H264 payload");
            }
            free(payload);
        } else if (payloadtype == 1 && payloadsize >= 11) {
            int sps_length = ((payload_in[6] & 0xff) << 8) | (payload_in[7] & 0xff);
            int pps_header = 8 + sps_length;
            if (sps_length > 0 && pps_header + 3 <= payloadsize) {
                int pps_length = ((payload_in[pps_header + 1] & 0xff) << 8)
                        | (payload_in[pps_header + 2] & 0xff);
                if (pps_length > 0 && pps_header + 3 + pps_length <= payloadsize) {
                    int config_length = sps_length + pps_length + 8;
                    unsigned char *config = malloc(config_length);
                    if (config) {
                        memset(config, 0, config_length);
                        config[3] = 1;
                        memcpy(config + 4, payload_in + 8, sps_length);
                        config[sps_length + 7] = 1;
                        memcpy(config + sps_length + 8,
                               payload_in + pps_header + 3, pps_length);
                        h264_decode_struct h264_data;
                        h264_data.data_len = config_length;
                        h264_data.data = config;
                        h264_data.frame_type = 0;
                        h264_data.pts = 0;
                        raop_rtp_mirror->callbacks.video_process(
                                raop_rtp_mirror->callbacks.cls, &h264_data);
                        free(config);
                    }
                } else {
                    logger_log(raop_rtp_mirror->logger, LOGGER_WARNING,
                               "Invalid mirror PPS length");
                }
            } else {
                logger_log(raop_rtp_mirror->logger, LOGGER_WARNING,
                           "Invalid mirror SPS length");
            }
        }
        free(payload_in);
    }

    if (stream_fd != -1) {
        shutdown(stream_fd, SHUT_RDWR);
        closesocket(stream_fd);
    }
    MUTEX_LOCK(raop_rtp_mirror->run_mutex);
    raop_rtp_mirror->mirror_stream_sock = -1;
    MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
    if (raop_rtp_mirror->callbacks.session_changed) {
        raop_rtp_mirror->callbacks.session_changed(
                raop_rtp_mirror->callbacks.cls, 0);
    }
    logger_log(raop_rtp_mirror->logger, LOGGER_INFO,
               "Exiting TCP raop_rtp_mirror_thread thread");
    return 0;
}

void
raop_rtp_start_mirror(raop_rtp_mirror_t *raop_rtp_mirror, int use_udp, unsigned short mirror_timing_rport, unsigned short * mirror_timing_lport,
                      unsigned short *mirror_data_lport)
{
    int use_ipv6 = 0;

    assert(raop_rtp_mirror);

    MUTEX_LOCK(raop_rtp_mirror->run_mutex);
    if (raop_rtp_mirror->running || !raop_rtp_mirror->joined) {
        MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
        return;
    }

    //raop_rtp_mirror->mirror_timing_rport = mirror_timing_rport;
    if (raop_rtp_mirror->remote_saddr.ss_family == AF_INET6) {
        use_ipv6 = 1;
    }
    use_ipv6 = 0;
    if (raop_rtp_init_mirror_sockets(raop_rtp_mirror, use_ipv6) < 0) {
        logger_log(raop_rtp_mirror->logger, LOGGER_INFO, "Initializing sockets failed");
        MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
        return;
    }
    if (mirror_timing_lport) *mirror_timing_lport = raop_rtp_mirror->mirror_timing_lport;
    if (mirror_data_lport) *mirror_data_lport = raop_rtp_mirror->mirror_data_lport;

    /* Create the thread and initialize running values */
    raop_rtp_mirror->running = 1;
    raop_rtp_mirror->joined = 0;

    THREAD_CREATE(raop_rtp_mirror->thread_mirror, raop_rtp_mirror_thread, raop_rtp_mirror);
    THREAD_CREATE(raop_rtp_mirror->thread_time, raop_rtp_mirror_thread_time, raop_rtp_mirror);
    MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
}

void raop_rtp_mirror_stop(raop_rtp_mirror_t *raop_rtp_mirror) {
    assert(raop_rtp_mirror);

    /* Check that we are running and thread is not
     * joined (should never be while still running) */
    MUTEX_LOCK(raop_rtp_mirror->run_mutex);
    if (!raop_rtp_mirror->running || raop_rtp_mirror->joined) {
        MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
        return;
    }
    raop_rtp_mirror->running = 0;
    int data_sock = raop_rtp_mirror->mirror_data_sock;
    int time_sock = raop_rtp_mirror->mirror_time_sock;
    int stream_sock = raop_rtp_mirror->mirror_stream_sock;
    MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);

    if (stream_sock != -1) shutdown(stream_sock, SHUT_RDWR);
    if (data_sock != -1) shutdown(data_sock, SHUT_RDWR);
    if (time_sock != -1) shutdown(time_sock, SHUT_RDWR);

    /* Join the thread */
    THREAD_JOIN(raop_rtp_mirror->thread_mirror);

    MUTEX_LOCK(raop_rtp_mirror->time_mutex);
    COND_SIGNAL(raop_rtp_mirror->time_cond);
    MUTEX_UNLOCK(raop_rtp_mirror->time_mutex);

    THREAD_JOIN(raop_rtp_mirror->thread_time);
    if (data_sock != -1) closesocket(data_sock);
    if (time_sock != -1) closesocket(time_sock);

    /* Mark thread as joined */
    MUTEX_LOCK(raop_rtp_mirror->run_mutex);
    raop_rtp_mirror->mirror_data_sock = -1;
    raop_rtp_mirror->mirror_time_sock = -1;
    raop_rtp_mirror->mirror_stream_sock = -1;
    raop_rtp_mirror->joined = 1;
    MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
}

void raop_rtp_mirror_destroy(raop_rtp_mirror_t *raop_rtp_mirror) {
    if (raop_rtp_mirror) {
        raop_rtp_mirror_stop(raop_rtp_mirror);
        MUTEX_DESTROY(raop_rtp_mirror->run_mutex);
        MUTEX_DESTROY(raop_rtp_mirror->time_mutex);
        COND_DESTROY(raop_rtp_mirror->time_cond);
        mirror_buffer_destroy(raop_rtp_mirror->buffer);
        free(raop_rtp_mirror);
    }
}

static int
raop_rtp_init_mirror_sockets(raop_rtp_mirror_t *raop_rtp_mirror, int use_ipv6)
{
    int dsock = -1, tsock = -1;
    unsigned short tport = 0, dport = 0;

    assert(raop_rtp_mirror);

    dsock = netutils_init_socket(&dport, use_ipv6, 0);
    tsock = netutils_init_socket(&tport, use_ipv6, 1);
    if (dsock == -1 || tsock == -1) {
        goto sockets_cleanup;
    }
    struct timeval timing_timeout;
    timing_timeout.tv_sec = 0;
    timing_timeout.tv_usec = 250000;
    setsockopt(tsock, SOL_SOCKET, SO_RCVTIMEO,
               (const char *)&timing_timeout, sizeof(timing_timeout));

    /* Listen to the data socket if using TCP */
    if (listen(dsock, 1) < 0)
        goto sockets_cleanup;


    /* Set socket descriptors */
    raop_rtp_mirror->mirror_data_sock = dsock;
    raop_rtp_mirror->mirror_time_sock = tsock;

    /* Set port values */
    raop_rtp_mirror->mirror_data_lport = dport;
    raop_rtp_mirror->mirror_timing_lport = tport;
    return 0;

    sockets_cleanup:
    if (tsock != -1) closesocket(tsock);
    if (dsock != -1) closesocket(dsock);
    return -1;
}
