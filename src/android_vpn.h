#ifndef __ANDROID_VPN_H__
#define __ANDROID_VPN_H__

struct android_vpn_config {
	char client_ip[65];
	char server_ip[65];
	int netmask;
	int mtu;
};

void android_vpn_set_enabled(int enabled);
int android_vpn_is_enabled(void);

void android_vpn_store_config(const char *client_ip, const char *server_ip,
			      int netmask, int mtu);
const struct android_vpn_config *android_vpn_get_config(void);

#endif
