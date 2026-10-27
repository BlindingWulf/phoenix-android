/* Original Phoenix code. Public Linux/Android child-process lifecycle APIs. */
#ifndef PHOENIX_PARENT_LIFECYCLE_H
#define PHOENIX_PARENT_LIFECYCLE_H
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/prctl.h>
#include <unistd.h>

static int phoenix_require_parent(void) {
    const pid_t parent = getppid();
    if (parent <= 1) {
        fprintf(stderr, "PHOENIX_RUNTIME_ERROR owning parent exited before startup\n");
        return -1;
    }
    if (prctl(PR_SET_PDEATHSIG, (unsigned long)SIGTERM, 0UL, 0UL, 0UL) != 0) {
        fprintf(stderr, "PHOENIX_RUNTIME_ERROR PR_SET_PDEATHSIG failed: %s\n", strerror(errno));
        return -1;
    }
    int configured = 0;
    if (prctl(PR_GET_PDEATHSIG, &configured, 0UL, 0UL, 0UL) != 0 || configured != SIGTERM) {
        fprintf(stderr, "PHOENIX_RUNTIME_ERROR parent-death SIGTERM could not be verified\n");
        return -1;
    }
    /* The parent may die between getppid and prctl; fail before launching code. */
    if (getppid() != parent) {
        fprintf(stderr, "PHOENIX_RUNTIME_ERROR owning parent changed during startup\n");
        return -1;
    }
    sigset_t termination;
    sigemptyset(&termination);
    sigaddset(&termination, SIGTERM);
    if (sigprocmask(SIG_UNBLOCK, &termination, NULL) != 0) {
        fprintf(stderr, "PHOENIX_RUNTIME_ERROR unblocking SIGTERM failed: %s\n", strerror(errno));
        return -1;
    }
    fprintf(stderr, "PHOENIX_PARENT_GUARD parent=%ld signal=SIGTERM\n", (long)parent);
    return 0;
}
#endif
