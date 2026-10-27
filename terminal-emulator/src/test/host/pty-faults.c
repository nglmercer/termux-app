#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>

int __real_grantpt(int fd);
pid_t __real_fork(void);

int __wrap_grantpt(int fd) {
    const char *failure = getenv("TERMUX_PTY_FAIL_TEST");
    if (failure && strcmp(failure, "grantpt") == 0) { errno = EACCES; return -1; }
    return __real_grantpt(fd);
}

pid_t __wrap_fork(void) {
    const char *failure = getenv("TERMUX_PTY_FAIL_TEST");
    if (failure && strcmp(failure, "fork") == 0) { errno = EAGAIN; return -1; }
    return __real_fork();
}
