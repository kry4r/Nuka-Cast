/**
 *  Copyright (C) 2018  Juho Vähä-Herttua
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 */

#include "plist/plist/plist.h"
#include <ctype.h>
#include <stdlib.h>
/* This file should be only included from raop.c as it defines static handler
 * functions and depends on raop internals */

typedef void (*raop_handler_t)(raop_conn_t *, http_request_t *,
                               http_response_t *, char **, int *);

static void
raop_handler_info(raop_conn_t *conn,
					   http_request_t *request, http_response_t *response,
					   char **response_data, int *response_datalen)
{
	const char *data;
	int datalen;
	data = http_request_get_data(request, &datalen);

	char info[] = {0x62,0x70,0x6c,0x69,0x73,0x74,0x30,0x30,0x10,0x0e,0x12,0x01,0xff,0xff,0xfc,0x59
			,0x61,0x75,0x64,0x69,0x6f,0x54,0x79,0x70,0x65,0xdf,0x10,0x0f,0x01,0x03,0x05,0x07
			,0x08,0x0a,0x0c,0x0e,0x0f,0x11,0x1b,0x24,0x26,0x28,0x2a,0x02,0x04,0x06,0x06,0x09
			,0x0b,0x0d,0x0d,0x10,0x12,0x1c,0x25,0x27,0x29,0x2b,0x54,0x74,0x79,0x70,0x65,0x58
			,0x64,0x69,0x73,0x70,0x6c,0x61,0x79,0x73,0x54,0x75,0x75,0x69,0x64,0x5f,0x10,0x11
			,0x61,0x75,0x64,0x69,0x6f,0x49,0x6e,0x70,0x75,0x74,0x46,0x6f,0x72,0x6d,0x61,0x74
			,0x73,0x58,0x66,0x65,0x61,0x74,0x75,0x72,0x65,0x73,0x5b,0x72,0x65,0x66,0x72,0x65
			,0x73,0x68,0x52,0x61,0x74,0x65,0xd4,0x1e,0x20,0x22,0x16,0x1f,0x21,0x21,0x1a,0x5f
			,0x10,0x11,0x61,0x61,0x3a,0x35,0x34,0x3a,0x30,0x31,0x3a,0x61,0x66,0x3a,0x63,0x33
			,0x3a,0x63,0x31,0x10,0x1e,0x10,0x64,0x55,0x6d,0x6f,0x64,0x65,0x6c,0x10,0x3c,0x56
			,0x68,0x65,0x69,0x67,0x68,0x74,0x5a,0x41,0x70,0x70,0x6c,0x65,0x54,0x56,0x32,0x2c
			,0x31,0x5d,0x73,0x6f,0x75,0x72,0x63,0x65,0x56,0x65,0x72,0x73,0x69,0x6f,0x6e,0x5f
			,0x10,0x11,0x6b,0x65,0x65,0x70,0x41,0x6c,0x69,0x76,0x65,0x4c,0x6f,0x77,0x50,0x6f
			,0x77,0x65,0x72,0xdc,0x2d,0x2f,0x31,0x32,0x33,0x34,0x35,0x36,0x28,0x39,0x3b,0x3c
			,0x2e,0x30,0x21,0x21,0x21,0x30,0x2e,0x37,0x38,0x3a,0x21,0x3d,0x5d,0x77,0x69,0x64
			,0x74,0x68,0x50,0x68,0x79,0x73,0x69,0x63,0x61,0x6c,0x56,0x32,0x32,0x30,0x2e,0x36
			,0x38,0xd3,0x14,0x16,0x18,0x15,0x17,0x15,0x5b,0x6f,0x76,0x65,0x72,0x73,0x63,0x61
			,0x6e,0x6e,0x65,0x64,0x5b,0x77,0x69,0x64,0x74,0x68,0x50,0x69,0x78,0x65,0x6c,0x73
			,0x4f,0x10,0x20,0xb0,0x77,0x27,0xd6,0xf6,0xcd,0x6e,0x08,0xb5,0x8e,0xde,0x52,0x5e
			,0xc3,0xcd,0xea,0xa2,0x52,0xad,0x9f,0x68,0x3f,0xeb,0x21,0x2e,0xf8,0xa2,0x05,0x24
			,0x65,0x54,0xe7,0x5a,0x6d,0x61,0x63,0x41,0x64,0x64,0x72,0x65,0x73,0x73,0x10,0x02
			,0xa1,0x2c,0x10,0x04,0xa2,0x13,0x19,0x5c,0x61,0x75,0x64,0x69,0x6f,0x46,0x6f,0x72
			,0x6d,0x61,0x74,0x73,0x54,0x6e,0x61,0x6d,0x65,0x08,0x52,0x76,0x76,0x13,0x00,0x00
			,0x00,0x1e,0x5a,0x7f,0xff,0xf7,0x5f,0x10,0x12,0x69,0x6e,0x70,0x75,0x74,0x4c,0x61
			,0x74,0x65,0x6e,0x63,0x79,0x4d,0x69,0x63,0x72,0x6f,0x73,0x5b,0x73,0x74,0x61,0x74
			,0x75,0x73,0x46,0x6c,0x61,0x67,0x73,0x57,0x41,0x70,0x70,0x6c,0x65,0x54,0x56,0xd4
			,0x1e,0x20,0x22,0x16,0x1f,0x21,0x21,0x17,0x57,0x64,0x65,0x66,0x61,0x75,0x6c,0x74
			,0x5f,0x10,0x24,0x32,0x65,0x33,0x38,0x38,0x30,0x30,0x36,0x2d,0x31,0x33,0x62,0x61
			,0x2d,0x34,0x30,0x34,0x31,0x2d,0x39,0x61,0x36,0x37,0x2d,0x32,0x35,0x64,0x64,0x34
			,0x61,0x34,0x33,0x64,0x35,0x33,0x36,0xd3,0x14,0x16,0x18,0x15,0x1a,0x15,0x5f,0x10
			,0x13,0x6f,0x75,0x74,0x70,0x75,0x74,0x4c,0x61,0x74,0x65,0x6e,0x63,0x79,0x4d,0x69
			,0x63,0x72,0x6f,0x73,0x5e,0x61,0x75,0x64,0x69,0x6f,0x4c,0x61,0x74,0x65,0x6e,0x63
			,0x69,0x65,0x73,0x58,0x72,0x6f,0x74,0x61,0x74,0x69,0x6f,0x6e,0x10,0x01,0x5c,0x68
			,0x65,0x69,0x67,0x68,0x74,0x50,0x69,0x78,0x65,0x6c,0x73,0x56,0x6d,0x61,0x78,0x46
			,0x50,0x53,0x58,0x64,0x65,0x76,0x69,0x63,0x65,0x49,0x44,0x5f,0x10,0x12,0x61,0x75
			,0x64,0x69,0x6f,0x4f,0x75,0x74,0x70,0x75,0x74,0x46,0x6f,0x72,0x6d,0x61,0x74,0x73
			,0x5f,0x10,0x24,0x65,0x30,0x66,0x66,0x38,0x61,0x32,0x37,0x2d,0x36,0x37,0x33,0x38
			,0x2d,0x33,0x64,0x35,0x36,0x2d,0x38,0x61,0x31,0x36,0x2d,0x63,0x63,0x35,0x33,0x61
			,0x61,0x63,0x65,0x65,0x39,0x32,0x35,0x5f,0x10,0x18,0x6b,0x65,0x65,0x70,0x41,0x6c
			,0x69,0x76,0x65,0x53,0x65,0x6e,0x64,0x53,0x74,0x61,0x74,0x73,0x41,0x73,0x42,0x6f
			,0x64,0x79,0x5e,0x68,0x65,0x69,0x67,0x68,0x74,0x50,0x68,0x79,0x73,0x69,0x63,0x61
			,0x6c,0x10,0x65,0x55,0x77,0x69,0x64,0x74,0x68,0x52,0x70,0x69,0x52,0x70,0x6b,0xa2
			,0x1d,0x23,0x11,0x04,0x38,0x11,0x07,0x80,0x00,0x19,0x00,0xb1,0x00,0xfa,0x01,0x8b
			,0x01,0x52,0x01,0x43,0x00,0x7f,0x02,0x22,0x01,0x64,0x01,0x97,0x01,0x6a,0x01,0x4e
			,0x00,0xbf,0x02,0x0c,0x02,0x67,0x02,0x99,0x01,0xb0,0x01,0x57,0x01,0x54,0x01,0x01
			,0x02,0x2b,0x00,0x0a,0x00,0x3a,0x00,0x95,0x00,0x4d,0x01,0xd7,0x02,0x91,0x01,0xf4
			,0x02,0x9f,0x01,0x9f,0x00,0x0f,0x01,0xa8,0x01,0x76,0x01,0x69,0x01,0xde,0x00,0x76
			,0x02,0x9c,0x01,0x20,0x00,0x97,0x00,0xa6,0x00,0x61,0x01,0x6d,0x00,0x3f,0x01,0x50
			,0x00,0xd3,0x00,0x9f,0x02,0xa2,0x02,0x93,0x02,0xa5,0x02,0x03,0x00,0xec,0x02,0x82
			,0x01,0x14,0x02,0x0e,0x00,0x6a,0x00,0x9d,0x00,0x08,0x02,0x1b,0x00,0x93,0x01,0x08
			,0x00,0x48,0x02,0x40,0x00,0x00,0x00,0x00,0x00,0x00,0x02,0x01,0x00,0x00,0x00,0x00
			,0x00,0x00,0x00,0x3e,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00
			,0x00,0x00,0x02,0xa8
	};
	size_t len = sizeof(info);
	*response_data = malloc(len);
	memcpy(*response_data, info, len);
	if (*response_data) {
        http_response_add_header(response, "Content-Type", "application/x-apple-binary-plist");
        //http_response_add_header(response, "Date", "Sun, 27 Jan 2019 10:32:17 GMT");
		*response_datalen = len;
	}
}

static void
raop_handler_pairsetup(raop_conn_t *conn,
                       http_request_t *request, http_response_t *response,
                       char **response_data, int *response_datalen)
{
	unsigned char public_key[32];
	const char *data;
	int datalen;

	data = http_request_get_data(request, &datalen);
	if (datalen != 32) {
		logger_log(conn->raop->logger, LOGGER_ERR, "Invalid pair-setup data");
		return;
	}

	pairing_get_public_key(conn->raop->pairing, public_key);
    pairing_session_set_setup_status(conn->pairing);

	*response_data = malloc(sizeof(public_key));
	if (*response_data) {
		http_response_add_header(response, "Content-Type", "application/octet-stream");
		memcpy(*response_data, public_key, sizeof(public_key));
		*response_datalen = sizeof(public_key);
	}
}

static void
raop_handler_pairverify(raop_conn_t *conn,
                        http_request_t *request, http_response_t *response,
                        char **response_data, int *response_datalen)
{
    if (pairing_session_check_handshake_status(conn->pairing)) {
        return;
    }
	unsigned char public_key[32];
	unsigned char signature[64];
	const unsigned char *data;
	int datalen;

	data = (unsigned char *) http_request_get_data(request, &datalen);
	if (datalen < 4) {
		logger_log(conn->raop->logger, LOGGER_ERR, "Invalid pair-verify data");
		return;
	}
	switch (data[0]) {
	case 1:
		if (datalen != 4 + 32 + 32) {
			logger_log(conn->raop->logger, LOGGER_ERR, "Invalid pair-verify data");
			return;
		}
		/* We can fall through these errors, the result will just be garbage... */
		if (pairing_session_handshake(conn->pairing, data + 4, data + 4 + 32)) {
			logger_log(conn->raop->logger, LOGGER_ERR, "Error initializing pair-verify handshake");
		}
		if (pairing_session_get_public_key(conn->pairing, public_key)) {
			logger_log(conn->raop->logger, LOGGER_ERR, "Error getting ECDH public key");
		}
		if (pairing_session_get_signature(conn->pairing, signature)) {
			logger_log(conn->raop->logger, LOGGER_ERR, "Error getting ED25519 signature");
		}
		*response_data = malloc(sizeof(public_key) + sizeof(signature));
		if (*response_data) {
			http_response_add_header(response, "Content-Type", "application/octet-stream");
			memcpy(*response_data, public_key, sizeof(public_key));
			memcpy(*response_data + sizeof(public_key), signature, sizeof(signature));
			*response_datalen = sizeof(public_key) + sizeof(signature);
		}
		break;
	case 0:
		if (datalen != 4 + 64) {
			logger_log(conn->raop->logger, LOGGER_ERR, "Invalid pair-verify data");
			return;
		}

		if (pairing_session_finish(conn->pairing, data + 4)) {
			logger_log(conn->raop->logger, LOGGER_ERR, "Incorrect pair-verify signature");
			http_response_set_disconnect(response, 1);
			return;
		}
        http_response_add_header(response, "Content-Type", "application/octet-stream");
		break;
	}
}

static void
raop_handler_fpsetup(raop_conn_t *conn,
                        http_request_t *request, http_response_t *response,
                        char **response_data, int *response_datalen)
{
	const unsigned char *data;
	int datalen;

	data = (unsigned char *) http_request_get_data(request, &datalen);
	if (datalen == 16) {
		*response_data = malloc(142);
		if (*response_data) {
            http_response_add_header(response, "Content-Type", "application/octet-stream");
			if (!fairplay_setup(conn->fairplay, data, (unsigned char *) *response_data)) {
				*response_datalen = 142;
			} else {
				// Handle error?
				free(*response_data);
				*response_data = NULL;
			}
		}
	} else if (datalen == 164) {
		*response_data = malloc(32);
		if (*response_data) {
            http_response_add_header(response, "Content-Type", "application/octet-stream");
			if (!fairplay_handshake(conn->fairplay, data, (unsigned char *) *response_data)) {
				*response_datalen = 32;
			} else {
				// Handle error?
				free(*response_data);
				*response_data = NULL;
			}
		}
	} else {
		logger_log(conn->raop->logger, LOGGER_ERR, "Invalid fp-setup data length");
		return;
	}
}

static void
raop_handler_options(raop_conn_t *conn,
                     http_request_t *request, http_response_t *response,
                     char **response_data, int *response_datalen)
{
	http_response_add_header(response, "Public", "SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER");
}

static int
raop_plist_uint(plist_t dict, const char *key, uint64_t *value)
{
	plist_t node;
	if (!dict || plist_get_node_type(dict) != PLIST_DICT || !key || !value) return -1;
	node = plist_dict_get_item(dict, key);
	if (!node || plist_get_node_type(node) != PLIST_UINT) return -1;
	plist_get_uint_val(node, value);
	return 0;
}

static void
raop_setup_reject(raop_conn_t *conn, http_response_t *response, const char *reason)
{
	logger_log(conn->raop->logger, LOGGER_WARNING, "Rejecting SETUP: %s", reason);
	http_response_add_header(response, "Connection", "close");
	http_response_set_disconnect(response, 1);
}

static int
raop_setup_response(http_response_t *response, char **response_data,
                    int *response_datalen, unsigned short event_port,
                    unsigned short timing_port, unsigned short data_port,
                    unsigned short control_port, uint64_t stream_type)
{
	plist_t root = NULL;
	plist_t streams = NULL;
	plist_t stream = NULL;
	char *encoded = NULL;
	uint32_t encoded_len = 0;
	int result = -1;

	root = plist_new_dict();
	streams = plist_new_array();
	stream = plist_new_dict();
	if (!root || !streams || !stream) goto cleanup;

	plist_dict_set_item(stream, "dataPort", plist_new_uint(data_port));
	plist_dict_set_item(stream, "type", plist_new_uint(stream_type));
	if (control_port) {
		plist_dict_set_item(stream, "controlPort", plist_new_uint(control_port));
	}
	plist_array_append_item(streams, stream);
	stream = NULL;
	if (event_port) {
		plist_dict_set_item(root, "eventPort", plist_new_uint(event_port));
	}
	plist_dict_set_item(root, "timingPort", plist_new_uint(timing_port));
	plist_dict_set_item(root, "streams", streams);
	streams = NULL;
	plist_to_bin(root, &encoded, &encoded_len);
	if (!encoded || encoded_len == 0 || encoded_len > 1024 * 1024) goto cleanup;

	*response_data = malloc(encoded_len);
	if (!*response_data) goto cleanup;
	memcpy(*response_data, encoded, encoded_len);
	*response_datalen = (int) encoded_len;
	http_response_add_header(response, "Content-Type", "application/x-apple-binary-plist");
	result = 0;

cleanup:
	free(encoded);
	if (stream) plist_free(stream);
	if (streams) plist_free(streams);
	if (root) plist_free(root);
	return result;
}

static void
raop_handler_setup(raop_conn_t *conn,
                   http_request_t *request, http_response_t *response,
                   char **response_data, int *response_datalen)
{
	const char *transport;
	const char *dacp_id;
	const char *active_remote_header;
	const char *data;
	int datalen = 0;
	int use_udp;
	plist_t root = NULL;
	plist_t eiv_node;
	plist_t ekey_node;
	plist_t streams;

	data = http_request_get_data(request, &datalen);
	if (!data || datalen < 8 || datalen > 8 * 1024 * 1024) {
		raop_setup_reject(conn, response, "invalid binary plist length");
		return;
	}
	plist_from_bin(data, (uint32_t) datalen, &root);
	if (!root || plist_get_node_type(root) != PLIST_DICT) {
		raop_setup_reject(conn, response, "invalid binary plist");
		if (root) plist_free(root);
		return;
	}

	dacp_id = http_request_get_header(request, "DACP-ID");
	active_remote_header = http_request_get_header(request, "Active-Remote");
	if (dacp_id && active_remote_header && conn->raop_rtp) {
		raop_rtp_remote_control_id(conn->raop_rtp, dacp_id, active_remote_header);
	}
	transport = http_request_get_header(request, "Transport");
	use_udp = transport && strncmp(transport, "RTP/AVP/TCP", 11) != 0;

	eiv_node = plist_dict_get_item(root, "eiv");
	ekey_node = plist_dict_get_item(root, "ekey");
	if (eiv_node || ekey_node) {
		char *eiv = NULL;
		char *ekey = NULL;
		uint64_t eiv_len = 0;
		uint64_t ekey_len = 0;
		uint64_t timing_port = 0;
		unsigned char aesiv[16];
		unsigned char aeskey[16];
		unsigned char ecdh_secret[32];

		if (!eiv_node || plist_get_node_type(eiv_node) != PLIST_DATA
				|| !ekey_node || plist_get_node_type(ekey_node) != PLIST_DATA
				|| raop_plist_uint(root, "timingPort", &timing_port) < 0
				|| timing_port == 0 || timing_port > 65535) {
			raop_setup_reject(conn, response, "missing encryption or timing fields");
			goto cleanup;
		}
		plist_get_data_val(eiv_node, &eiv, &eiv_len);
		plist_get_data_val(ekey_node, &ekey, &ekey_len);
		if (!eiv || eiv_len != sizeof(aesiv) || !ekey || ekey_len != 72) {
			free(eiv);
			free(ekey);
			raop_setup_reject(conn, response, "invalid eiv or ekey length");
			goto cleanup;
		}
		memcpy(aesiv, eiv, sizeof(aesiv));
		free(eiv);
		if (fairplay_decrypt(conn->fairplay, (unsigned char *) ekey, aeskey) != 0) {
			free(ekey);
			raop_setup_reject(conn, response, "FairPlay key exchange incomplete");
			goto cleanup;
		}
		free(ekey);
		pairing_get_ecdh_secret_key(conn->pairing, ecdh_secret);
		if (conn->raop_rtp) raop_rtp_destroy(conn->raop_rtp);
		if (conn->raop_rtp_mirror) raop_rtp_mirror_destroy(conn->raop_rtp_mirror);
		conn->raop_rtp = raop_rtp_init(conn->raop->logger, &conn->raop->callbacks,
				conn->remote, conn->remotelen, aeskey, aesiv, ecdh_secret,
				(unsigned short) timing_port);
		conn->raop_rtp_mirror = raop_rtp_mirror_init(conn->raop->logger,
				&conn->raop->callbacks, conn->remote, conn->remotelen, aeskey,
				ecdh_secret, (unsigned short) timing_port);
		if (!conn->raop_rtp || !conn->raop_rtp_mirror) {
			if (conn->raop_rtp) raop_rtp_destroy(conn->raop_rtp);
			if (conn->raop_rtp_mirror) raop_rtp_mirror_destroy(conn->raop_rtp_mirror);
			conn->raop_rtp = NULL;
			conn->raop_rtp_mirror = NULL;
			raop_setup_reject(conn, response, "RTP initialization failed");
			goto cleanup;
		}
		conn->crypto_ready = 1;
		conn->mirror_ready = 0;
		conn->audio_ready = 0;
		logger_log(conn->raop->logger, LOGGER_INFO, "SETUP encryption initialized");
		goto cleanup;
	}

	streams = plist_dict_get_item(root, "streams");
	if (!conn->crypto_ready || !streams || plist_get_node_type(streams) != PLIST_ARRAY
			|| plist_array_get_size(streams) == 0) {
		raop_setup_reject(conn, response, "stream SETUP before encryption SETUP");
		goto cleanup;
	}

	{
		plist_t stream = plist_array_get_item(streams, 0);
		uint64_t type = 0;
		if (!stream || raop_plist_uint(stream, "type", &type) < 0) {
			raop_setup_reject(conn, response, "missing stream type");
			goto cleanup;
		}
		if (type == 110) {
			uint64_t stream_id = 0;
			unsigned short timing_port = 0;
			unsigned short data_port = 0;
			if (conn->mirror_ready || raop_plist_uint(stream, "streamConnectionID", &stream_id) < 0) {
				raop_setup_reject(conn, response, "invalid mirror stream");
				goto cleanup;
			}
			raop_rtp_init_mirror_aes(conn->raop_rtp_mirror, stream_id);
			raop_rtp_start_mirror(conn->raop_rtp_mirror, use_udp, 0,
					&timing_port, &data_port);
			if (!data_port || raop_setup_response(response, response_data,
					response_datalen, conn->raop->port, timing_port, data_port, 0, 110) < 0) {
				raop_setup_reject(conn, response, "mirror socket initialization failed");
				goto cleanup;
			}
			conn->mirror_ready = 1;
		} else if (type == 96) {
			uint64_t remote_control = 0;
			uint64_t remote_timing = 0;
			unsigned short control_port = 0;
			unsigned short timing_port = 0;
			unsigned short data_port = 0;
			if (conn->audio_ready
					|| raop_plist_uint(stream, "controlPort", &remote_control) < 0
					|| remote_control > 65535
					|| (raop_plist_uint(stream, "timingPort", &remote_timing) < 0
						&& raop_plist_uint(root, "timingPort", &remote_timing) < 0)
					|| remote_timing > 65535) {
				raop_setup_reject(conn, response, "invalid audio stream ports");
				goto cleanup;
			}
			raop_rtp_start_audio(conn->raop_rtp, use_udp,
					(unsigned short) remote_control, (unsigned short) remote_timing,
					&control_port, &timing_port, &data_port);
			if (!data_port || raop_setup_response(response, response_data,
					response_datalen, 0, timing_port, data_port, control_port, 96) < 0) {
				raop_setup_reject(conn, response, "audio socket initialization failed");
				goto cleanup;
			}
			conn->audio_ready = 1;
		} else {
			raop_setup_reject(conn, response, "unsupported stream type");
		}
	}

cleanup:
	plist_free(root);
}

static void
raop_handler_get_parameter(raop_conn_t *conn,
                           http_request_t *request, http_response_t *response,
                           char **response_data, int *response_datalen)
{
	const char *content_type;
	const char *data;
	int datalen;

	content_type = http_request_get_header(request, "Content-Type");
	data = http_request_get_data(request, &datalen);
	if (content_type && data && datalen > 0 && !strcmp(content_type, "text/parameters")) {
		const char *current = data;
		const char *end = data + datalen;

		while (current < end) {
			const char *next;
			int handled = 0;
			size_t remaining = (size_t) (end - current);

			/* This is a bit ugly, but seems to be how airport works too */
			if (remaining >= 8 && !memcmp(current, "volume\r\n", 8)) {
				const char volume[] = "volume: 0.0\r\n";

				http_response_add_header(response, "Content-Type", "text/parameters");
				*response_data = strdup(volume);
				if (*response_data) {
					*response_datalen = strlen(*response_data);
				}
				handled = 1;
			}

			next = NULL;
			if (remaining >= 2) {
				const char *scan;
				for (scan = current; scan + 1 < end; scan++) {
					if (scan[0] == '\r' && scan[1] == '\n') {
						next = scan;
						break;
					}
				}
			}
			if (next && !handled) {
				logger_log(conn->raop->logger, LOGGER_WARNING,
				           "Found an unknown parameter: %.*s", (next - current), current);
				current = next + 2;
			} else if (next) {
				current = next + 2;
			} else {
				current = end;
			}
		}
	}
}

static void
raop_handler_set_parameter(raop_conn_t *conn,
                           http_request_t *request, http_response_t *response,
                           char **response_data, int *response_datalen)
{
	const char *content_type;
	const char *data;
	int datalen;

	content_type = http_request_get_header(request, "Content-Type");
	data = http_request_get_data(request, &datalen);
	if (!content_type) return;
	if (!strcmp(content_type, "text/parameters")) {
		char *datastr;
		datastr = calloc(1, datalen+1);
		if (data && datastr && conn->raop_rtp) {
			memcpy(datastr, data, datalen);
			if (!strncmp(datastr, "volume: ", 8)) {
				float vol = 0.0;
				sscanf(datastr+8, "%f", &vol);
				raop_rtp_set_volume(conn->raop_rtp, vol);
			} else if (!strncmp(datastr, "progress: ", 10)) {
				unsigned int start, curr, end;
				sscanf(datastr+10, "%u/%u/%u", &start, &curr, &end);
				raop_rtp_set_progress(conn->raop_rtp, start, curr, end);
			}
		} else if (!conn->raop_rtp) {
			logger_log(conn->raop->logger, LOGGER_WARNING, "RAOP not initialized at SET_PARAMETER");
		}
		free(datastr);
	} else if (!strcmp(content_type, "image/jpeg") || !strcmp(content_type, "image/png")) {
		logger_log(conn->raop->logger, LOGGER_INFO, "Got image data of %d bytes", datalen);
		if (conn->raop_rtp) {
			raop_rtp_set_coverart(conn->raop_rtp, data, datalen);
		} else {
			logger_log(conn->raop->logger, LOGGER_WARNING, "RAOP not initialized at SET_PARAMETER coverart");
		}
	} else if (!strcmp(content_type, "application/x-dmap-tagged")) {
		logger_log(conn->raop->logger, LOGGER_INFO, "Got metadata of %d bytes", datalen);
		if (conn->raop_rtp) {
			raop_rtp_set_metadata(conn->raop_rtp, data, datalen);
		} else {
			logger_log(conn->raop->logger, LOGGER_WARNING, "RAOP not initialized at SET_PARAMETER metadata");
		}
	}
}


static void
raop_handler_feedback(raop_conn_t *conn,
                           http_request_t *request, http_response_t *response,
                           char **response_data, int *response_datalen)
{
    logger_log(conn->raop->logger, LOGGER_DEBUG, "raop_handler_feedback");
}

static void
raop_handler_record(raop_conn_t *conn,
                      http_request_t *request, http_response_t *response,
                      char **response_data, int *response_datalen)
{
    logger_log(conn->raop->logger, LOGGER_DEBUG, "raop_handler_record");
    http_response_add_header(response, "Audio-Latency", "11025");
    http_response_add_header(response, "Audio-Jack-Status", "connected; type=analog");
}
