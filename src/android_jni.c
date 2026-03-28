#include <jni.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netdb.h>
#include <android/log.h>

#include "android_vpn.h"
#include "common.h"
#include "client.h"

#define LOG_TAG "iodine-android"

static JavaVM *java_vm;
static jobject service_ref;
static int dns_fd = -1;
static int tun_fd = -1;
static int nameserv_family = AF_UNSPEC;
static int raw_mode = 1;
static int autodetect_frag_size = 1;
static int max_downstream_frag_size = 3072;
static char password_buf[33];

static int
parse_resolver_endpoint(const char *resolver, char **host_out, int *port_out)
{
	const char *host_start;
	const char *port_start = NULL;
	const char *closing_bracket;
	char *host_copy;
	size_t host_len;
	long parsed_port = DNS_PORT;

	if (resolver == NULL || resolver[0] == '\0')
		return -1;

	host_start = resolver;
	if (resolver[0] == '[') {
		closing_bracket = strchr(resolver, ']');
		if (closing_bracket == NULL)
			return -1;
		host_start = resolver + 1;
		host_len = (size_t) (closing_bracket - host_start);
		if (closing_bracket[1] == ':')
			port_start = closing_bracket + 2;
	} else {
		const char *last_colon = strrchr(resolver, ':');
		const char *first_colon = strchr(resolver, ':');
		if (last_colon != NULL && first_colon == last_colon) {
			host_len = (size_t) (last_colon - resolver);
			port_start = last_colon + 1;
		} else {
			host_len = strlen(resolver);
		}
	}

	if (port_start != NULL && port_start[0] != '\0') {
		char *endptr = NULL;
		parsed_port = strtol(port_start, &endptr, 10);
		if (endptr == NULL || *endptr != '\0' || parsed_port < 1 || parsed_port > 65535)
			return -1;
	}

	host_copy = malloc(host_len + 1);
	if (host_copy == NULL)
		return -1;

	memcpy(host_copy, host_start, host_len);
	host_copy[host_len] = '\0';

	*host_out = host_copy;
	*port_out = (int) parsed_port;
	return 0;
}

static char *
copy_jstring(JNIEnv *env, jstring value)
{
	const char *utf;
	char *copy;

	if (value == NULL)
		return NULL;

	utf = (*env)->GetStringUTFChars(env, value, NULL);
	if (utf == NULL)
		return NULL;

	copy = strdup(utf);
	(*env)->ReleaseStringUTFChars(env, value, utf);
	return copy;
}

static void
clear_service_ref(JNIEnv *env)
{
	if (service_ref != NULL) {
		(*env)->DeleteGlobalRef(env, service_ref);
		service_ref = NULL;
	}
}

static JNIEnv *
get_env(void)
{
	JNIEnv *env = NULL;

	if ((*java_vm)->AttachCurrentThread(java_vm, &env, NULL) != JNI_OK)
		return NULL;

	return env;
}

static bool
protect_socket(int fd)
{
	JNIEnv *env;
	jclass cls;
	jmethodID method;
	jboolean protected_ok;

	env = get_env();
	if (env == NULL || service_ref == NULL)
		return false;

	cls = (*env)->GetObjectClass(env, service_ref);
	method = (*env)->GetMethodID(env, cls, "protectSocket", "(I)Z");
	protected_ok = (*env)->CallBooleanMethod(env, service_ref, method, fd);
	return protected_ok == JNI_TRUE;
}

static void
emit_log(const char *line)
{
	JNIEnv *env;
	jclass cls;
	jmethodID method;
	jstring message;

	__android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", line);

	env = get_env();
	if (env == NULL || service_ref == NULL)
		return;

	cls = (*env)->GetObjectClass(env, service_ref);
	method = (*env)->GetMethodID(env, cls, "emitLog", "(Ljava/lang/String;)V");
	message = (*env)->NewStringUTF(env, line);
	(*env)->CallVoidMethod(env, service_ref, method, message);
	(*env)->DeleteLocalRef(env, message);
}

void
android_jni_emit_log(const char *line)
{
	if (line == NULL || line[0] == '\0')
		return;
	emit_log(line);
}

static void
parse_options(char *options)
{
	char *token;

	nameserv_family = AF_UNSPEC;
	raw_mode = 1;
	autodetect_frag_size = 1;
	max_downstream_frag_size = 3072;
	client_set_lazymode(1);
	client_set_selecttimeout(4);
	client_set_hostname_maxlen(0xFF);
	client_set_handshake_timeout_multiplier(1);
	client_set_doh_url(NULL);

	if (options == NULL)
		return;

	token = strtok(options, " \t\r\n");
	while (token != NULL) {
		if (!strcmp(token, "-4")) {
			nameserv_family = AF_INET;
		} else if (!strcmp(token, "-6")) {
			nameserv_family = AF_INET6;
		} else if (!strcmp(token, "-r")) {
			raw_mode = 0;
		} else if (!strcmp(token, "-U")) {
			token = strtok(NULL, " \t\r\n");
			if (token != NULL)
				client_set_doh_url(token);
		} else if (!strcmp(token, "-m")) {
			token = strtok(NULL, " \t\r\n");
			if (token != NULL) {
				autodetect_frag_size = 0;
				max_downstream_frag_size = atoi(token);
			}
		} else if (!strcmp(token, "-M")) {
			token = strtok(NULL, " \t\r\n");
			if (token != NULL)
				client_set_hostname_maxlen(atoi(token));
		} else if (!strcmp(token, "-T")) {
			token = strtok(NULL, " \t\r\n");
			if (token != NULL)
				client_set_qtype(token);
		} else if (!strcmp(token, "-O")) {
			token = strtok(NULL, " \t\r\n");
			if (token != NULL)
				client_set_downenc(token);
		} else if (!strcmp(token, "-L")) {
			int lazymode = 1;
			token = strtok(NULL, " \t\r\n");
			if (token != NULL)
				lazymode = atoi(token);
			client_set_lazymode(lazymode < 0 ? 0 : (lazymode > 1 ? 1 : lazymode));
		} else if (!strcmp(token, "-I")) {
			int timeout = 4;
			token = strtok(NULL, " \t\r\n");
			if (token != NULL)
				timeout = atoi(token);
			client_set_selecttimeout(timeout < 1 ? 1 : timeout);
		}

		token = strtok(NULL, " \t\r\n");
	}
}

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved)
{
	(void) reserved;
	java_vm = vm;
	return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_se_kryo_iodine_IodineVpnService_nativeAttach(JNIEnv *env, jobject thiz)
{
	clear_service_ref(env);
	service_ref = (*env)->NewGlobalRef(env, thiz);
}

JNIEXPORT void JNICALL
Java_se_kryo_iodine_IodineVpnService_nativeDetach(JNIEnv *env, jobject thiz)
{
	(void) thiz;
	clear_service_ref(env);
}

JNIEXPORT jboolean JNICALL
Java_se_kryo_iodine_IodineVpnService_nativeHandshake(JNIEnv *env, jobject thiz,
	jstring resolver, jstring domain, jstring password, jstring options)
{
	char *resolver_copy = NULL;
	char *domain_copy = NULL;
	char *password_copy = NULL;
	char *options_copy = NULL;
	char *resolver_host = NULL;
	struct sockaddr_storage nameservaddr;
	int nameservaddr_len;
	int dns_family;
	int resolver_port = DNS_PORT;
	char *errormsg = NULL;

	(void) thiz;

	resolver_copy = copy_jstring(env, resolver);
	domain_copy = copy_jstring(env, domain);
	password_copy = copy_jstring(env, password);
	options_copy = copy_jstring(env, options);

	if (domain_copy == NULL || password_copy == NULL) {
		emit_log("Missing domain or password.");
		goto fail;
	}

	client_init();
	android_vpn_set_enabled(1);
	parse_options(options_copy);

	if (client_get_doh_url() == NULL) {
		if (resolver_copy == NULL || resolver_copy[0] == '\0') {
			emit_log("Missing resolver.");
			goto fail;
		}

		if (parse_resolver_endpoint(resolver_copy, &resolver_host, &resolver_port) != 0) {
			emit_log("Failed to parse resolver address.");
			goto fail;
		}

		nameservaddr_len = get_addr(resolver_host, resolver_port, nameserv_family, 0, &nameservaddr);
		if (nameservaddr_len < 0) {
			emit_log("Failed to resolve nameserver.");
			goto fail;
		}
	}

	if (resolver_copy != NULL && strncmp(resolver_copy, "127.0.0.1:", 10) == 0) {
		client_set_handshake_timeout_multiplier(5);
		emit_log("Using extended handshake timeouts for local DoH relay.");
	}

	if (check_topdomain(domain_copy, 0, &errormsg)) {
		emit_log("Invalid delegated domain.");
		goto fail;
	}

	if (client_get_doh_url() == NULL)
		client_set_nameserver(&nameservaddr, nameservaddr_len);
	client_set_topdomain(domain_copy);
	memset(password_buf, 0, sizeof(password_buf));
	strncpy(password_buf, password_copy, sizeof(password_buf) - 1);
	client_set_password(password_buf);

	dns_family = (client_get_doh_url() == NULL) ? nameservaddr.ss_family :
		((nameserv_family == AF_UNSPEC) ? AF_INET : nameserv_family);
	dns_fd = open_dns_from_host(NULL, 0, dns_family, AI_PASSIVE);
	if (dns_fd < 0) {
		emit_log("Failed to open client UDP socket.");
		goto fail;
	}
	if (!protect_socket(dns_fd)) {
		emit_log("VpnService.protect() failed for client socket.");
		goto fail;
	}

	emit_log("Running iodine handshake.");
	if (client_handshake(dns_fd, raw_mode, autodetect_frag_size, max_downstream_frag_size)) {
		if (client_get_doh_url() != NULL) {
			if (errno == ENOSYS) {
				emit_log("DoH is not compiled into this Android build yet.");
			} else if (errno != 0) {
				char errmsg[256];
				snprintf(errmsg, sizeof(errmsg),
					 "DoH handshake failed: %s", strerror(errno));
				emit_log(errmsg);
			}
		}
		emit_log("Handshake failed.");
		goto fail;
	}

	emit_log("Handshake complete.");
	free(resolver_copy);
	free(resolver_host);
	free(domain_copy);
	free(password_copy);
	free(options_copy);
	return JNI_TRUE;

fail:
	if (dns_fd >= 0) {
		close_dns(dns_fd);
		dns_fd = -1;
	}
	android_vpn_set_enabled(0);
	free(resolver_copy);
	free(resolver_host);
	free(domain_copy);
	free(password_copy);
	free(options_copy);
	return JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_se_kryo_iodine_IodineVpnService_nativeGetClientIp(JNIEnv *env, jobject thiz)
{
	(void) thiz;
	return (*env)->NewStringUTF(env, android_vpn_get_config()->client_ip);
}

JNIEXPORT jstring JNICALL
Java_se_kryo_iodine_IodineVpnService_nativeGetServerIp(JNIEnv *env, jobject thiz)
{
	(void) thiz;
	return (*env)->NewStringUTF(env, android_vpn_get_config()->server_ip);
}

JNIEXPORT jint JNICALL
Java_se_kryo_iodine_IodineVpnService_nativeGetNetmask(JNIEnv *env, jobject thiz)
{
	(void) env;
	(void) thiz;
	return android_vpn_get_config()->netmask;
}

JNIEXPORT jint JNICALL
Java_se_kryo_iodine_IodineVpnService_nativeGetMtu(JNIEnv *env, jobject thiz)
{
	(void) env;
	(void) thiz;
	return android_vpn_get_config()->mtu;
}

JNIEXPORT jint JNICALL
Java_se_kryo_iodine_IodineVpnService_nativeRunTunnel(JNIEnv *env, jobject thiz, jint tunfd)
{
	(void) env;
	(void) thiz;
	tun_fd = tunfd;
	emit_log("Tunnel loop started.");
	client_tunnel(tun_fd, dns_fd);
	emit_log("Tunnel loop stopped.");
	return 0;
}

JNIEXPORT void JNICALL
Java_se_kryo_iodine_IodineVpnService_nativeStop(JNIEnv *env, jobject thiz)
{
	(void) env;
	(void) thiz;

	client_stop();

	if (dns_fd >= 0) {
		close_dns(dns_fd);
		dns_fd = -1;
	}
	if (tun_fd >= 0) {
		close(tun_fd);
		tun_fd = -1;
	}

	android_vpn_set_enabled(0);
}
