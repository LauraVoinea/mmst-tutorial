/*
 * nonet <command> [args...]: run command with no network.
 *
 * A seccomp filter fails socket() with EACCES for every family but AF_UNIX
 * (the Erlang VM uses those) and io_uring_setup with ENOSYS. Inherited, and
 * permanent. Needs no privileges, only no_new_privs.
 *
 *   cc -O2 -static -o nonet nonet.c
 */
#include <errno.h>
#include <stddef.h>
#include <stdio.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>

#if defined(__x86_64__)
#  define ARCH AUDIT_ARCH_X86_64
#elif defined(__aarch64__)
#  define ARCH AUDIT_ARCH_AARCH64
#else
#  error "x86_64 and aarch64 only"
#endif

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: nonet <command> [args...]\n");
        return 2;
    }
    struct sock_filter filter[] = {
        /* Another syscall ABI (i386 on x86_64) would bypass the checks: kill. */
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, ARCH, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        /* io_uring can open sockets without socket(). */
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_io_uring_setup, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | (ENOSYS & SECCOMP_RET_DATA)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_socket, 0, 3),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AF_UNIX, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | (EACCES & SECCOMP_RET_DATA)),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog prog = { .len = sizeof filter / sizeof filter[0], .filter = filter };
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) { perror("nonet: no_new_privs"); return 126; }
    if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) != 0) { perror("nonet: seccomp"); return 126; }
    execvp(argv[1], argv + 1);
    perror("nonet: exec");
    return 127;
}
