#include <string.h>

#include "android_vpn.h"

static int vpn_enabled = 0;
static struct android_vpn_config vpn_config;

void
android_vpn_set_enabled(int enabled)
{
	vpn_enabled = enabled;
}

int
android_vpn_is_enabled(void)
{
	return vpn_enabled;
}

void
android_vpn_store_config(const char *client_ip, const char *server_ip,
			 int netmask, int mtu)
{
	strncpy(vpn_config.client_ip, client_ip, sizeof(vpn_config.client_ip));
	vpn_config.client_ip[sizeof(vpn_config.client_ip) - 1] = 0;

	strncpy(vpn_config.server_ip, server_ip, sizeof(vpn_config.server_ip));
	vpn_config.server_ip[sizeof(vpn_config.server_ip) - 1] = 0;

	vpn_config.netmask = netmask;
	vpn_config.mtu = mtu;
}

const struct android_vpn_config *
android_vpn_get_config(void)
{
	return &vpn_config;
}
