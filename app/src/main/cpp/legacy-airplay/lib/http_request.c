/**
 *  Copyright (C) 2011-2012  Juho Vähä-Herttua
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

#include <stdlib.h>
#include <string.h>
#include <assert.h>

#include "http_request.h"
#include "http_parser.h"

#define HTTP_REQUEST_MAX_URL (8 * 1024)
#define HTTP_REQUEST_MAX_HEADERS (64 * 1024)
#define HTTP_REQUEST_MAX_HEADER_FIELDS 128
#define HTTP_REQUEST_MAX_BODY (8 * 1024 * 1024)

struct http_request_s {
	http_parser parser;
	http_parser_settings parser_settings;

	const char *method;
	char *url;

	char **headers;
	int headers_size;
	int headers_index;
	size_t headers_bytes;

	char *data;
	int datalen;

	int complete;
};

static int
on_url(http_parser *parser, const char *at, size_t length)
{
	http_request_t *request = parser->data;
	size_t urllen = request->url ? strlen(request->url) : 0;
	char *resized;

	if (length > HTTP_REQUEST_MAX_URL || urllen > HTTP_REQUEST_MAX_URL - length) return 1;
	resized = realloc(request->url, urllen + length + 1);
	if (!resized) return 1;
	request->url = resized;

	memcpy(request->url + urllen, at, length);
	request->url[urllen + length] = '\0';
	return 0;
}

static int
append_header(http_request_t *request, const char *at, size_t length)
{
	char *current;
	char *resized;
	size_t current_len;

	if (!request || !at || request->headers_index < 0
			|| request->headers_index >= request->headers_size) return 1;
	if (length > HTTP_REQUEST_MAX_HEADERS
			|| request->headers_bytes > HTTP_REQUEST_MAX_HEADERS - length) return 1;
	current = request->headers[request->headers_index];
	current_len = current ? strlen(current) : 0;
	resized = realloc(current, current_len + length + 1);
	if (!resized) return 1;
	memcpy(resized + current_len, at, length);
	resized[current_len + length] = '\0';
	request->headers[request->headers_index] = resized;
	request->headers_bytes += length;
	return 0;
}

static int
on_header_field(http_parser *parser, const char *at, size_t length)
{
	http_request_t *request = parser->data;

	/* Check if our index is a value */
	if (request->headers_index%2 == 1) {
		request->headers_index++;
	}

	/* Allocate space for new field-value pair */
	if (request->headers_index == request->headers_size) {
		char **resized;
		if (request->headers_size >= HTTP_REQUEST_MAX_HEADER_FIELDS) return 1;
		request->headers_size += 2;
		resized = realloc(request->headers, request->headers_size * sizeof(char *));
		if (!resized) return 1;
		request->headers = resized;
		request->headers[request->headers_index] = NULL;
		request->headers[request->headers_index+1] = NULL;
	}

	return append_header(request, at, length);
}

static int
on_header_value(http_parser *parser, const char *at, size_t length)
{
	http_request_t *request = parser->data;

	/* Check if our index is a field */
	if (request->headers_index%2 == 0) {
		request->headers_index++;
	}

	return append_header(request, at, length);
}

static int
on_body(http_parser *parser, const char *at, size_t length)
{
	http_request_t *request = parser->data;
	char *resized;
	size_t current = (size_t) request->datalen;

	if (length > HTTP_REQUEST_MAX_BODY || current > HTTP_REQUEST_MAX_BODY - length) return 1;
	resized = realloc(request->data, current + length + 1);
	if (!resized) return 1;
	request->data = resized;

	memcpy(request->data + current, at, length);
	request->datalen += length;
	request->data[request->datalen] = '\0';
	return 0;
}

static int
on_message_complete(http_parser *parser)
{
	http_request_t *request = parser->data;

	request->method = http_method_str(request->parser.method);
	request->complete = 1;
	return 0;
}

http_request_t *
http_request_init(void)
{
	http_request_t *request;

	request = calloc(1, sizeof(http_request_t));
	if (!request) {
		return NULL;
	}
	http_parser_init(&request->parser, HTTP_REQUEST);
	request->parser.data = request;

	request->parser_settings.on_url = &on_url;
	request->parser_settings.on_header_field = &on_header_field;
	request->parser_settings.on_header_value = &on_header_value;
	request->parser_settings.on_body = &on_body;
	request->parser_settings.on_message_complete = &on_message_complete;

	return request;
}

void
http_request_destroy(http_request_t *request)
{
	int i;

	if (request) {
		free(request->url);
		for (i=0; i<request->headers_size; i++) {
			free(request->headers[i]);
		}
		free(request->headers);
		free(request->data);
		free(request);
	}
}

int
http_request_add_data(http_request_t *request, const char *data, int datalen)
{
	int ret;

	assert(request);

	if (!data || datalen <= 0) return 0;
	ret = http_parser_execute(&request->parser,
	                          &request->parser_settings,
	                          data, datalen);
	return ret;
}

int
http_request_is_complete(http_request_t *request)
{
	assert(request);
	return request->complete;
}

int
http_request_has_error(http_request_t *request)
{
	assert(request);
	return (HTTP_PARSER_ERRNO(&request->parser) != HPE_OK);
}

const char *
http_request_get_error_name(http_request_t *request)
{
	assert(request);
	return http_errno_name(HTTP_PARSER_ERRNO(&request->parser));
}

const char *
http_request_get_error_description(http_request_t *request)
{
	assert(request);
	return http_errno_description(HTTP_PARSER_ERRNO(&request->parser));
}

const char *
http_request_get_method(http_request_t *request)
{
	assert(request);
	return request->method;
}

const char *
http_request_get_url(http_request_t *request)
{
	assert(request);
	return request->url;
}

const char *
http_request_get_header(http_request_t *request, const char *name)
{
	int i;

	assert(request);

	for (i=0; i+1<request->headers_size; i+=2) {
		if (request->headers[i] && request->headers[i+1]
				&& !strcmp(request->headers[i], name)) {
			return request->headers[i+1];
		}
	}
	return NULL;
}

const char *
http_request_get_data(http_request_t *request, int *datalen)
{
	assert(request);

	if (datalen) {
		*datalen = request->datalen;
	}
	return request->data;
}
