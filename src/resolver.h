#ifndef __RESOLVER_H__
#define __RESOLVER_H__

#include <stddef.h>

#include "common.h"

enum resolver_transport {
	RESOLVER_TRANSPORT_UDP = 0,
	RESOLVER_TRANSPORT_DOH = 1
};

int resolver_init_udp(int fd, struct sockaddr_storage *addr, socklen_t addrlen);
int resolver_init_doh(const char *url);
void resolver_close(void);
int resolver_send_packet(const char *packet, size_t len, int timeout_sec);
int resolver_poll(int timeout_sec);
int resolver_has_pending(void);
int resolver_recv_packet(char *buf, size_t buflen,
			 struct sockaddr_storage *from, socklen_t *fromlen);
int resolver_get_fd(void);
enum resolver_transport resolver_get_transport(void);

#endif
