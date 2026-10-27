#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>
int main(void) {
    pid_t child = fork();
    if (child < 0) return 1;
    if (child == 0) { for (;;) pause(); }
    int fd = (int) syscall(__NR_pidfd_open, child, 0);
    if (fd < 0) { perror("pidfd_open"); kill(child, SIGKILL); waitpid(child, 0, 0); return 2; }
    if (syscall(__NR_pidfd_send_signal, fd, SIGTERM, 0, 0) != 0) {
        perror("pidfd_send_signal"); kill(child, SIGKILL); waitpid(child, 0, 0); close(fd); return 3;
    }
    int status;
    if (waitpid(child, &status, 0) != child || !WIFSIGNALED(status) || WTERMSIG(status) != SIGTERM) return 4;
    errno = 0;
    if (syscall(__NR_pidfd_send_signal, fd, SIGTERM, 0, 0) != -1 || errno != ESRCH) return 5;
    close(fd);
    puts("pidfd child signal and exited-process rejection passed");
    return 0;
}
