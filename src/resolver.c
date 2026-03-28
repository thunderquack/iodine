#include <string.h>

#include "resolver.h"

static enum resolver_transport resolver_transport = RESOLVER_TRANSPORT_UDP;

int resolver_udp_init(int fd, struct sockaddr_storage *addr, socklen_t addrlen);
void resolver_udp_close(void);
int resolver_udp_send_packet(const char *packet, size_t len, int timeout_sec);
int resolver_udp_poll(int timeout_sec);
int resolver_udp_recv_packet(char *buf, size_t buflen,
			     struct sockaddr_storage *from, socklen_t *fromlen);
int resolver_udp_get_fd(void);

int resolver_doh_init(const char *url);
void resolver_doh_close(void);
int resolver_doh_send_packet(const char *packet, size_t len, int timeout_sec);
int resolver_doh_poll(int timeout_sec);
int resolver_doh_has_pending(void);
int resolver_doh_recv_packet(char *buf, size_t buflen,
			     struct sockaddr_storage *from, socklen_t *fromlen);

int
resolver_init_udp(int fd, struct sockaddr_storage *addr, socklen_t addrlen)
{
	resolver_transport = RESOLVER_TRANSPORT_UDP;
	return resolver_udp_init(fd, addr, addrlen);
}

int
resolver_init_doh(const char *url)
{
	resolver_transport = RESOLVER_TRANSPORT_DOH;
	return resolver_doh_init(url);
}

void
resolver_close(void)
{
	if (resolver_transport == RESOLVER_TRANSPORT_DOH)
		resolver_doh_close();
	else
		resolver_udp_close();
}

int
resolver_send_packet(const char *packet, size_t len, int timeout_sec)
{
	if (resolver_transport == RESOLVER_TRANSPORT_DOH)
		return resolver_doh_send_packet(packet, len, timeout_sec);

	return resolver_udp_send_packet(packet, len, timeout_sec);
}

int
resolver_poll(int timeout_sec)
{
	if (resolver_transport == RESOLVER_TRANSPORT_DOH)
		return resolver_doh_poll(timeout_sec);

	return resolver_udp_poll(timeout_sec);
}

int
resolver_has_pending(void)
{
	if (resolver_transport == RESOLVER_TRANSPORT_DOH)
		return resolver_doh_has_pending();

	return 0;
}

int
resolver_recv_packet(char *buf, size_t buflen,
		     struct sockaddr_storage *from, socklen_t *fromlen)
{
	if (resolver_transport == RESOLVER_TRANSPORT_DOH)
		return resolver_doh_recv_packet(buf, buflen, from, fromlen);

	return resolver_udp_recv_packet(buf, buflen, from, fromlen);
}

int
resolver_get_fd(void)
{
	if (resolver_transport == RESOLVER_TRANSPORT_DOH)
		return -1;

	return resolver_udp_get_fd();
}

enum resolver_transport
resolver_get_transport(void)
{
	return resolver_transport;
}
