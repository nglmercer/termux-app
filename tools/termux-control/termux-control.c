#define _GNU_SOURCE
#include <ctype.h>
#include <errno.h>
#include <limits.h>
#include <poll.h>
#include <time.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

#define MAX_FRAME 65536u
#define MAX_REQUEST 4096u

struct span { const char *start, *end; };
static void ws(const char **p) { while (**p == ' ' || **p == '\n' || **p == '\r' || **p == '\t') ++*p; }
static int strval(const char **p) {
    if (*(*p)++ != '"') return 0;
    while (**p && **p != '"') {
        unsigned char c = (unsigned char)*(*p)++;
        if (c < 0x20) return 0;
        if (c == '\\') {
            char e = *(*p)++;
            if (!e) return 0;
            if (e == 'u') { for (int i = 0; i < 4; ++i) if (!isxdigit((unsigned char)*(*p)++)) return 0; }
            else if (!strchr("\"\\/bfnrt", e)) return 0;
        }
    }
    if (**p != '"') return 0;
    ++*p; return 1;
}
static int value(const char **p, int depth) {
    if (depth > 16) return 0;
    ws(p);
    if (**p == '"') return strval(p);
    if (**p == '{' || **p == '[') {
        int object = **p == '{'; char end = object ? '}' : ']'; ++*p; ws(p);
        if (**p == end) { ++*p; return 1; }
        for (;;) {
            if (object) { if (**p != '"' || !strval(p)) return 0; ws(p); if (*(*p)++ != ':') return 0; }
            if (!value(p, depth + 1)) return 0;
            ws(p); if (**p == end) { ++*p; return 1; }
            if (*(*p)++ != ',') return 0;
            ws(p);
        }
    }
    const char *s = *p;
    if (!strncmp(s, "true", 4)) { *p += 4; return 1; }
    if (!strncmp(s, "false", 5)) { *p += 5; return 1; }
    if (!strncmp(s, "null", 4)) { *p += 4; return 1; }
    if (**p == '-') ++*p;
    if (**p == '0') ++*p;
    else { if (**p < '1' || **p > '9') return 0; while (isdigit((unsigned char)**p)) ++*p; }
    if (**p == '.') { ++*p; if (!isdigit((unsigned char)**p)) return 0; while (isdigit((unsigned char)**p)) ++*p; }
    if (**p == 'e' || **p == 'E') { ++*p; if (**p == '+' || **p == '-') ++*p; if (!isdigit((unsigned char)**p)) return 0; while (isdigit((unsigned char)**p)) ++*p; }
    return *p != s;
}
/* Find an unescaped ASCII member name in a fully validated object; reject duplicates. */
static int member(struct span object, const char *name, struct span *out) {
    const char *p = object.start; int found = 0;
    ws(&p); if (*p++ != '{') return -1; ws(&p);
    if (*p == '}') return 0;
    for (;;) {
        const char *key = p;
        if (*p != '"' || !strval(&p)) return -1;
        size_t keylen = (size_t)(p - key - 2); ws(&p); if (*p++ != ':') return -1; ws(&p);
        const char *start = p; if (!value(&p, 1)) return -1;
        if (strlen(name) == keylen && !memcmp(key + 1, name, keylen)) {
            if (found) return -1;
            found = 1; out->start = start; out->end = p;
        }
        ws(&p); if (*p == '}') { ++p; ws(&p); return p == object.end ? found : -1; }
        if (*p++ != ',') return -1;
        ws(&p);
    }
}
static int equals(struct span s, const char *v) { return (size_t)(s.end - s.start) == strlen(v) && !memcmp(s.start, v, strlen(v)); }
static int response_ok(char *json, int id, int hello) {
    const char *p = json; struct span root = {json, NULL}, version, rid, result, error;
    if (!value(&p, 0)) return -1;
    ws(&p); if (*p) return -1; root.end = p;
    int r = member(root, "result", &result), e = member(root, "error", &error);
    if (member(root, "jsonrpc", &version) != 1 || !equals(version, "\"2.0\"") ||
        member(root, "id", &rid) != 1 || (r == 1) == (e == 1) || r < 0 || e < 0) return -1;
    char expected[16]; snprintf(expected, sizeof(expected), "%d", id);
    if (!equals(rid, expected)) return -1;
    if (e == 1) return 0;
    if (hello) { struct span v; if (member(result, "version", &v) != 1 || !equals(v, "1")) return -1; }
    return 1;
}
static long long now_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts)) return -1;
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}
static int ready(int fd, short events, long long deadline) {
    for (;;) {
        long long now = now_ms();
        if (now < 0 || now >= deadline) { errno = ETIMEDOUT; return -1; }
        int remaining = (int)(deadline - now);
        struct pollfd pfd = { .fd = fd, .events = events };
        int n = poll(&pfd, 1, remaining);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { if (!n) errno = ETIMEDOUT; return -1; }
        if (pfd.revents & events) return 0;
        errno = EIO; return -1;
    }
}
static int all_write(int fd, const void *buf, size_t n, long long deadline) {
    const unsigned char *p = buf;
    while (n) {
        if (ready(fd, POLLOUT, deadline)) return -1;
        ssize_t w = send(fd, p, n, MSG_NOSIGNAL);
        if (w < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
        if (w <= 0) return -1;
        p += w; n -= (size_t)w;
    }
    return 0;
}
static int all_read(int fd, void *buf, size_t n, long long deadline) {
    unsigned char *p = buf;
    while (n) {
        if (ready(fd, POLLIN, deadline)) return -1;
        ssize_t r = recv(fd, p, n, 0);
        if (r < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
        if (r <= 0) return -1;
        p += r; n -= (size_t)r;
    }
    return 0;
}
static int exchange(int fd, const char *request, int id, int hello, const unsigned char *binary, size_t binary_len) {
    size_t n = strlen(request); unsigned char h[4] = {(unsigned char)(n >> 24), (unsigned char)(n >> 16), (unsigned char)(n >> 8), (unsigned char)n};
    long long now = now_ms();
    if (now < 0) { fprintf(stderr, "termux-control: clock unavailable\n"); return -1; }
    long long deadline = now + 5000;
    if (n == 0 || n > MAX_FRAME || all_write(fd, h, 4, deadline) || all_write(fd, request, n, deadline)) {
        fprintf(stderr, "termux-control: connection failed or timed out\n"); return -1;
    }
    if (binary) {
        unsigned char header[4] = {(unsigned char)(binary_len >> 24), (unsigned char)(binary_len >> 16),
                                   (unsigned char)(binary_len >> 8), (unsigned char)binary_len};
        // A denied request may cause the server to reply and close before reading
        // the binary frame. Still try to read its structured response on send failure.
        if (all_write(fd, header, 4, deadline) || all_write(fd, binary, binary_len, deadline)) {
            if (all_read(fd, h, 4, deadline)) {
                fprintf(stderr, "termux-control: connection failed or timed out\n"); return -1;
            }
        } else if (all_read(fd, h, 4, deadline)) {
            fprintf(stderr, "termux-control: connection failed or timed out\n"); return -1;
        }
    } else if (all_read(fd, h, 4, deadline)) {
        fprintf(stderr, "termux-control: connection failed or timed out\n"); return -1;
    }
    uint32_t len = ((uint32_t)h[0] << 24) | ((uint32_t)h[1] << 16) | ((uint32_t)h[2] << 8) | h[3];
    if (len == 0 || len > MAX_FRAME) { fprintf(stderr, "termux-control: invalid response length\n"); return -1; }
    char *body = malloc((size_t)len + 1);
    if (!body) { fprintf(stderr, "termux-control: out of memory\n"); return -1; }
    if (all_read(fd, body, len, deadline) || memchr(body, 0, len)) { free(body); fprintf(stderr, "termux-control: incomplete response\n"); return -1; }
    body[len] = 0;
    int result = response_ok(body, id, hello);
    if (result == 0) fprintf(stderr, "termux-control: server error: %s\n", body);
    else if (result < 0) fprintf(stderr, "termux-control: invalid server response\n");
    else if (!hello) puts(body);
    free(body); return result == 1 ? 0 : (result == 0 ? 1 : -1);
}
static int append(char *buf, size_t cap, size_t *used, const char *s) {
    size_t n = strlen(s); if (n >= cap - *used) return -1;
    memcpy(buf + *used, s, n + 1); *used += n; return 0;
}
static int quoted(char *buf, size_t cap, size_t *used, const char *s) {
    if (append(buf, cap, used, "\"")) return -1;
    for (const unsigned char *p = (const unsigned char *)s; *p; ++p) {
        char tmp[7];
        if (*p == '"' || *p == '\\') { tmp[0] = '\\'; tmp[1] = (char)*p; tmp[2] = 0; }
        else if (*p < 0x20) snprintf(tmp, sizeof(tmp), "\\u%04x", *p);
        else { tmp[0] = (char)*p; tmp[1] = 0; }
        if (append(buf, cap, used, tmp)) return -1;
    }
    return append(buf, cap, used, "\"");
}
static int number(const char *s, int min, int max) {
    char *end; errno = 0; long n = strtol(s, &end, 10);
    return !errno && *s && !*end && n >= min && n <= max;
}
static void usage(void) {
    fprintf(stderr, "Usage: termux-control [--socket PATH] capabilities|list|create COLS ROWS [NAME]|rename ID NAME|resize ID COLS ROWS|close ID|write ID < FILE\n");
}
int main(int argc, char **argv) {
    const char *path = getenv("TERMUX_CONTROL_SOCKET"); char default_path[sizeof(((struct sockaddr_un *)0)->sun_path)];
    int i = 1;
    if (i < argc && !strcmp(argv[i], "--socket")) { if (++i >= argc) { usage(); return 2; } path = argv[i++]; }
    if (!path || !*path) {
        const char *home = getenv("HOME");
        if (!home || snprintf(default_path, sizeof(default_path), "%s/../control-ipc/control.sock", home) >= (int)sizeof(default_path)) {
            fprintf(stderr, "termux-control: HOME or socket path is invalid\n"); return 2;
        }
        path = default_path;
    }
    if (i >= argc || strlen(path) >= sizeof(((struct sockaddr_un *)0)->sun_path)) { usage(); return 2; }
    const char *cmd = argv[i++]; int count = argc - i; char req[MAX_REQUEST] = ""; size_t used = 0;
    const char *method = NULL;
    if (!strcmp(cmd, "list") && count == 0) method = "session.list";
    else if (!strcmp(cmd, "capabilities") && count == 0) method = "system.capabilities";
    else if (!strcmp(cmd, "create") && (count == 2 || count == 3) && number(argv[i], 2, 500) && number(argv[i + 1], 2, 250)) method = "session.create";
    else if (!strcmp(cmd, "rename") && count == 2) method = "session.rename";
    else if (!strcmp(cmd, "resize") && count == 3 && number(argv[i + 1], 2, 500) && number(argv[i + 2], 2, 250)) method = "session.resize";
    else if (!strcmp(cmd, "close") && count == 1) method = "session.close";
    else if (!strcmp(cmd, "write") && count == 1) method = "session.write";
    else { usage(); return 2; }
    unsigned char binary[MAX_FRAME + 1];
    size_t binary_len = 0;
    if (!strcmp(cmd, "write")) {
        binary_len = fread(binary, 1, sizeof(binary), stdin);
        if (ferror(stdin)) { fprintf(stderr, "termux-control: cannot read stdin\n"); return 1; }
        if (binary_len == 0 || binary_len > MAX_FRAME) {
            fprintf(stderr, "termux-control: input must contain 1 to 65536 bytes\n"); return 2;
        }
    }
    if (append(req, sizeof(req), &used, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":") || quoted(req, sizeof(req), &used, method) || append(req, sizeof(req), &used, ",\"params\":{")) goto too_long;
    if (!strcmp(cmd, "rename") || !strcmp(cmd, "resize") || !strcmp(cmd, "close") || !strcmp(cmd, "write")) {
        if (append(req, sizeof(req), &used, "\"id\":") || quoted(req, sizeof(req), &used, argv[i])) goto too_long;
    }
    if (!strcmp(cmd, "write")) {
        char length[40]; snprintf(length, sizeof(length), ",\"length\":%zu", binary_len);
        if (append(req, sizeof(req), &used, length)) goto too_long;
    }
    if (!strcmp(cmd, "rename")) { if (append(req, sizeof(req), &used, ",\"name\":") || quoted(req, sizeof(req), &used, argv[i + 1])) goto too_long; }
    if (!strcmp(cmd, "create") || !strcmp(cmd, "resize")) {
        int offset = !strcmp(cmd, "resize") ? 1 : 0; char dims[80];
        snprintf(dims, sizeof(dims), "%s\"columns\":%ld,\"rows\":%ld", offset ? "," : "", strtol(argv[i + offset], NULL, 10), strtol(argv[i + offset + 1], NULL, 10));
        if (append(req, sizeof(req), &used, dims)) goto too_long;
        if (!offset && count == 3) { if (append(req, sizeof(req), &used, ",\"name\":") || quoted(req, sizeof(req), &used, argv[i + 2])) goto too_long; }
    }
    if (append(req, sizeof(req), &used, "}}")) goto too_long;
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) { perror("termux-control: socket"); return 1; }
    struct timeval timeout = { .tv_sec = 5, .tv_usec = 0 };
    if (setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) || setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout))) { perror("termux-control: timeout"); close(fd); return 1; }
    struct sockaddr_un addr = { .sun_family = AF_UNIX }; memcpy(addr.sun_path, path, strlen(path) + 1);
    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr))) {
        int saved = errno;
        long long now = now_ms();
        if ((saved != EINPROGRESS && saved != EAGAIN) || now < 0 || ready(fd, POLLOUT, now + 5000)) {
            if (saved != EINPROGRESS && saved != EAGAIN) errno = saved;
            fprintf(stderr, "termux-control: cannot connect to local control socket: %s\n", strerror(errno)); close(fd); return 1;
        }
        int error = 0; socklen_t error_size = sizeof(error);
        if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &error_size) || error != 0) {
            if (error != 0) errno = error;
            fprintf(stderr, "termux-control: cannot connect to local control socket: %s\n", strerror(errno)); close(fd); return 1;
        }
    }
    struct ucred peer; socklen_t peer_size = sizeof(peer);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &peer, &peer_size) || peer_size != sizeof(peer) || peer.uid != getuid()) {
        fprintf(stderr, "termux-control: socket peer is not the current UID\n"); close(fd); return 1;
    }
    int handshake = exchange(fd, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"system.hello\",\"params\":{\"version\":1}}", 1, 1, NULL, 0);
    if (handshake != 0) { close(fd); return 1; }
    int result = exchange(fd, req, 2, 0, binary_len ? binary : NULL, binary_len);
    if (result < 0 && strcmp(cmd, "list") && strcmp(cmd, "capabilities"))
        fprintf(stderr, "termux-control: request outcome may be unknown; inspect session state before retrying\n");
    close(fd); return result == 0 ? 0 : 1;
too_long:
    fprintf(stderr, "termux-control: request is too long\n"); return 2;
}
