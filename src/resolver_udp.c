#include <errno.h>
#include <string.h>
#include <sys/select.h>

#include "resolver.h"

static int resolver_fd = -1;
static struct sockaddr_storage resolver_addr;
static socklen_t resolver_addrlen = 0;

int
resolver_udp_init(int fd, struct sockaddr_storage *addr, socklen_t addrlen)
{
	resolver_fd = fd;
	memcpy(&resolver_addr, addr, addrlen);
	resolver_addrlen = addrlen;
	return 0;
}

void
resolver_udp_close(void)
{
	resolver_fd = -1;
	resolver_addrlen = 0;
	memset(&resolver_addr, 0, sizeof(resolver_addr));
}

int
resolver_udp_send_packet(const char *packet, size_t len, int timeout_sec)
{
	(void) timeout_sec;

	if (sendto(resolver_fd, packet, len, 0,
		   (struct sockaddr*) &resolver_addr, resolver_addrlen) < 0) {
		return -1;
	}

	return 0;
}

int
resolver_udp_poll(int timeout_sec)
{
	fd_set fds;
	struct timeval tv;
	int r;

	FD_ZERO(&fds);
	FD_SET(resolver_fd, &fds);
	tv.tv_sec = timeout_sec;
	tv.tv_usec = 0;

	r = select(resolver_fd + 1, &fds, NULL, NULL, &tv);
	if (r < 0)
		return -1;
	if (r == 0)
		return 0;
	return 1;
}

int
resolver_udp_recv_packet(char *buf, size_t buflen,
			 struct sockaddr_storage *from, socklen_t *fromlen)
{
	return recvfrom(resolver_fd, buf, buflen, 0,
			(struct sockaddr*) from, fromlen);
}

int
resolver_udp_get_fd(void)
{
	return resolver_fd;
}
