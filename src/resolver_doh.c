#include <errno.h>
#include <stdlib.h>
#include <string.h>

#include "common.h"
#include "resolver.h"

#ifdef HAVE_LIBCURL
#include <curl/curl.h>

struct doh_response_buffer {
	char *data;
	size_t len;
	size_t cap;
};

static CURL *resolver_curl;
static char *resolver_doh_url;
static char *resolver_pending;
static size_t resolver_pending_len;

void resolver_doh_close(void);
static size_t resolver_doh_write_cb(char *ptr, size_t size, size_t nmemb, void *userdata);
static int resolver_doh_store_pending(struct doh_response_buffer *buffer);

static int
base64url_encode(const unsigned char *src, size_t srclen, char *dst, size_t dstlen)
{
	static const char alphabet[] =
		"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
	size_t inpos = 0;
	size_t outpos = 0;

	while (inpos + 3 <= srclen) {
		unsigned int v = ((unsigned int) src[inpos] << 16) |
				 ((unsigned int) src[inpos + 1] << 8) |
				 (unsigned int) src[inpos + 2];
		if (outpos + 4 >= dstlen)
			return -1;
		dst[outpos++] = alphabet[(v >> 18) & 0x3f];
		dst[outpos++] = alphabet[(v >> 12) & 0x3f];
		dst[outpos++] = alphabet[(v >> 6) & 0x3f];
		dst[outpos++] = alphabet[v & 0x3f];
		inpos += 3;
	}

	if (srclen - inpos == 1) {
		unsigned int v = (unsigned int) src[inpos] << 16;
		if (outpos + 2 >= dstlen)
			return -1;
		dst[outpos++] = alphabet[(v >> 18) & 0x3f];
		dst[outpos++] = alphabet[(v >> 12) & 0x3f];
	} else if (srclen - inpos == 2) {
		unsigned int v = ((unsigned int) src[inpos] << 16) |
				 ((unsigned int) src[inpos + 1] << 8);
		if (outpos + 3 >= dstlen)
			return -1;
		dst[outpos++] = alphabet[(v >> 18) & 0x3f];
		dst[outpos++] = alphabet[(v >> 12) & 0x3f];
		dst[outpos++] = alphabet[(v >> 6) & 0x3f];
	}

	dst[outpos] = '\0';
	return (int) outpos;
}

static size_t
resolver_doh_write_cb(char *ptr, size_t size, size_t nmemb, void *userdata)
{
	struct doh_response_buffer *buffer = userdata;
	size_t chunk_len = size * nmemb;
	size_t needed = buffer->len + chunk_len + 1;
	char *grown;

	if (chunk_len == 0)
		return 0;

	if (needed > buffer->cap) {
		size_t new_cap = needed * 2;
		grown = realloc(buffer->data, new_cap);
		if (grown == NULL)
			return 0;
		buffer->data = grown;
		buffer->cap = new_cap;
	}

	memcpy(buffer->data + buffer->len, ptr, chunk_len);
	buffer->len += chunk_len;
	buffer->data[buffer->len] = '\0';
	return chunk_len;
}

static int
resolver_doh_store_pending(struct doh_response_buffer *buffer)
{
	free(resolver_pending);
	resolver_pending = buffer->data;
	resolver_pending_len = buffer->len;
	buffer->data = NULL;
	buffer->len = 0;
	buffer->cap = 0;
	return 0;
}

int
resolver_doh_init(const char *url)
{
	if (url == NULL || url[0] == '\0') {
		errno = EINVAL;
		return -1;
	}

	resolver_doh_close();

	if (curl_global_init(CURL_GLOBAL_DEFAULT) != CURLE_OK) {
		errno = EIO;
		return -1;
	}

	resolver_curl = curl_easy_init();
	if (resolver_curl == NULL) {
		curl_global_cleanup();
		errno = ENOMEM;
		return -1;
	}

	resolver_doh_url = strdup(url);
	if (resolver_doh_url == NULL) {
		curl_easy_cleanup(resolver_curl);
		resolver_curl = NULL;
		curl_global_cleanup();
		errno = ENOMEM;
		return -1;
	}

	return 0;
}

void
resolver_doh_close(void)
{
	free(resolver_pending);
	resolver_pending = NULL;
	resolver_pending_len = 0;

	free(resolver_doh_url);
	resolver_doh_url = NULL;

	if (resolver_curl != NULL) {
		curl_easy_cleanup(resolver_curl);
		resolver_curl = NULL;
		curl_global_cleanup();
	}
}

int
resolver_doh_send_packet(const char *packet, size_t len, int timeout_sec)
{
	struct curl_slist *headers = NULL;
	struct doh_response_buffer response = { 0 };
	CURLcode curl_code;
	long response_code = 0;
	long http_version = CURL_HTTP_VERSION_NONE;
	char *full_url;
	char *encoded;
	int encoded_len;
	char separator = '?';
	size_t base_len;

	if (resolver_curl == NULL || resolver_doh_url == NULL) {
		errno = ENOTCONN;
		return -1;
	}

	base_len = strlen(resolver_doh_url);
	encoded = malloc(((len + 2) / 3) * 4 + 1);
	if (encoded == NULL) {
		errno = ENOMEM;
		return -1;
	}

	encoded_len = base64url_encode((const unsigned char *) packet, len,
				       encoded, ((len + 2) / 3) * 4 + 1);
	if (encoded_len < 0) {
		free(encoded);
		errno = EMSGSIZE;
		return -1;
	}

	if (strchr(resolver_doh_url, '?') != NULL)
		separator = '&';

	full_url = malloc(base_len + 6 + encoded_len + 1);
	if (full_url == NULL) {
		free(encoded);
		errno = ENOMEM;
		return -1;
	}

	memcpy(full_url, resolver_doh_url, base_len);
	full_url[base_len] = separator;
	memcpy(full_url + base_len + 1, "dns=", 4);
	memcpy(full_url + base_len + 5, encoded, encoded_len + 1);
	free(encoded);

	headers = curl_slist_append(headers, "Accept: application/dns-message");

	curl_easy_reset(resolver_curl);
	curl_easy_setopt(resolver_curl, CURLOPT_URL, full_url);
	curl_easy_setopt(resolver_curl, CURLOPT_HTTPGET, 1L);
	curl_easy_setopt(resolver_curl, CURLOPT_HTTPHEADER, headers);
	curl_easy_setopt(resolver_curl, CURLOPT_HTTP_VERSION, CURL_HTTP_VERSION_2_0);
	curl_easy_setopt(resolver_curl, CURLOPT_ACCEPT_ENCODING, "");
	curl_easy_setopt(resolver_curl, CURLOPT_NOSIGNAL, 1L);
	curl_easy_setopt(resolver_curl, CURLOPT_WRITEFUNCTION, resolver_doh_write_cb);
	curl_easy_setopt(resolver_curl, CURLOPT_WRITEDATA, &response);
	curl_easy_setopt(resolver_curl, CURLOPT_USERAGENT, "iodine-doh/1");
	if (timeout_sec > 0) {
		curl_easy_setopt(resolver_curl, CURLOPT_TIMEOUT, (long) timeout_sec);
		curl_easy_setopt(resolver_curl, CURLOPT_CONNECTTIMEOUT, (long) timeout_sec);
	}

	curl_code = curl_easy_perform(resolver_curl);
	free(full_url);
	curl_slist_free_all(headers);
	if (curl_code != CURLE_OK) {
		free(response.data);
		errno = EIO;
		return -1;
	}

	curl_easy_getinfo(resolver_curl, CURLINFO_RESPONSE_CODE, &response_code);
	curl_easy_getinfo(resolver_curl, CURLINFO_HTTP_VERSION, &http_version);
	if (response_code != 200 || http_version != CURL_HTTP_VERSION_2_0) {
		free(response.data);
		errno = EPROTO;
		return -1;
	}

	return resolver_doh_store_pending(&response);
}

int
resolver_doh_poll(int timeout_sec)
{
	(void) timeout_sec;
	return resolver_pending_len > 0 ? 1 : 0;
}

int
resolver_doh_has_pending(void)
{
	return resolver_pending_len > 0;
}

int
resolver_doh_recv_packet(char *buf, size_t buflen,
			 struct sockaddr_storage *from, socklen_t *fromlen)
{
	size_t copy_len;

	if (resolver_pending == NULL || resolver_pending_len == 0) {
		errno = EAGAIN;
		return -1;
	}

	copy_len = MIN(buflen, resolver_pending_len);
	memcpy(buf, resolver_pending, copy_len);
	free(resolver_pending);
	resolver_pending = NULL;
	resolver_pending_len = 0;

	if (from != NULL)
		memset(from, 0, sizeof(*from));
	if (fromlen != NULL)
		*fromlen = 0;

	return (int) copy_len;
}

#else

int
resolver_doh_init(const char *url)
{
	(void) url;
	errno = ENOSYS;
	return -1;
}

void
resolver_doh_close(void)
{
}

int
resolver_doh_send_packet(const char *packet, size_t len, int timeout_sec)
{
	(void) packet;
	(void) len;
	(void) timeout_sec;
	errno = ENOSYS;
	return -1;
}

int
resolver_doh_poll(int timeout_sec)
{
	(void) timeout_sec;
	return 0;
}

int
resolver_doh_has_pending(void)
{
	return 0;
}

int
resolver_doh_recv_packet(char *buf, size_t buflen,
			 struct sockaddr_storage *from, socklen_t *fromlen)
{
	(void) buf;
	(void) buflen;
	(void) from;
	(void) fromlen;
	errno = ENOSYS;
	return -1;
}

#endif
