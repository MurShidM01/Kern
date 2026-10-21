/*
 * Kern PTY bridge (JNI).
 *
 * Android's bionic has no reliable forkpty()/login_tty() combination, so a real pseudo-terminal is
 * opened directly: posix_openpt -> grantpt -> unlockpt -> ptsname, then fork(), and in the child
 * setsid() + TIOCSCTTY + dup2() onto 0/1/2 before execve(). The parent keeps the master fd and reads
 * the merged stdout/stderr stream from it. This is the same mechanism Termux uses, and it is what
 * gives the guest a controlling terminal (job control, interactive prompts, TUI apps).
 *
 * Reads use poll() with a short timeout so the Kotlin reader loop can observe coroutine cancellation
 * between polls instead of blocking forever on a native read().
 */

#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

/* Must match Pty.kt. */
#define KERN_READ_TIMEOUT (-2)
#define KERN_READ_ERROR (-1)
#define KERN_POLL_TIMEOUT_MS (100)

static char **to_c_string_array(JNIEnv *env, jobjectArray array, jint *count_out) {
    if (array == NULL) {
        *count_out = 0;
        return NULL;
    }
    jint count = (*env)->GetArrayLength(env, array);
    char **result = (char **) malloc(sizeof(char *) * (count + 1));
    if (result == NULL) {
        *count_out = 0;
        return NULL;
    }
    for (jint i = 0; i < count; i++) {
        jstring element = (jstring) (*env)->GetObjectArrayElement(env, array, i);
        result[i] = element == NULL ? NULL : (char *) (*env)->GetStringUTFChars(env, element, NULL);
    }
    result[count] = NULL;
    *count_out = count;
    return result;
}

static void release_c_string_array(JNIEnv *env, jobjectArray array, char **strings, jint count) {
    if (strings == NULL) return;
    for (jint i = 0; i < count; i++) {
        if (strings[i] == NULL) continue;
        jstring element = (jstring) (*env)->GetObjectArrayElement(env, array, i);
        if (element != NULL) (*env)->ReleaseStringUTFChars(env, element, strings[i]);
    }
    free(strings);
}

static void set_window_size(int fd, jint rows, jint cols) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) (rows < 0 ? 0 : rows);
    ws.ws_col = (unsigned short) (cols < 0 ? 0 : cols);
    ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT jint JNICALL
Java_com_aliedu_kern_core_terminal_pty_Pty_createSubprocess(
        JNIEnv *env, jobject thiz,
        jobjectArray argv, jstring cwd, jobjectArray envp,
        jint rows, jint cols, jintArray pidOut) {
    (void) thiz;

    jint argc = 0;
    jint envc = 0;
    char **argv_c = to_c_string_array(env, argv, &argc);
    char **envp_c = to_c_string_array(env, envp, &envc);
    const char *cwd_c = cwd == NULL ? NULL : (*env)->GetStringUTFChars(env, cwd, NULL);

    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) {
        goto fail_open;
    }
    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        goto fail_pty;
    }

    set_window_size(master, rows, cols);

    char *slave_name = strdup(ptsname(master));
    if (slave_name == NULL) {
        goto fail_pty;
    }

    pid_t pid = fork();
    if (pid < 0) {
        free(slave_name);
        goto fail_pty;
    }

    if (pid == 0) {
        /* Child: become a session leader, adopt the slave as the controlling terminal, then exec. */
        setsid();

        int slave = open(slave_name, O_RDWR);
        if (slave < 0) {
            _exit(127);
        }
        ioctl(slave, TIOCSCTTY, NULL);
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        close(master);

        if (cwd_c != NULL) {
            chdir(cwd_c);
        }
        execve(argv_c[0], argv_c, envp_c);
        _exit(127);
    }

    /* Parent. */
    free(slave_name);
    if (pidOut != NULL) {
        jint pid_value = (jint) pid;
        (*env)->SetIntArrayRegion(env, pidOut, 0, 1, &pid_value);
    }
    release_c_string_array(env, argv, argv_c, argc);
    release_c_string_array(env, envp, envp_c, envc);
    if (cwd_c != NULL) (*env)->ReleaseStringUTFChars(env, cwd, cwd_c);
    return master;

fail_pty:
    close(master);
fail_open:
    release_c_string_array(env, argv, argv_c, argc);
    release_c_string_array(env, envp, envp_c, envc);
    if (cwd_c != NULL) (*env)->ReleaseStringUTFChars(env, cwd, cwd_c);
    return -1;
}

JNIEXPORT void JNICALL
Java_com_aliedu_kern_core_terminal_pty_Pty_setPtyWindowSize(
        JNIEnv *env, jobject thiz, jint fd, jint rows, jint cols) {
    (void) env;
    (void) thiz;
    if (fd < 0) return;
    set_window_size(fd, rows, cols);
}

JNIEXPORT jint JNICALL
Java_com_aliedu_kern_core_terminal_pty_Pty_read(
        JNIEnv *env, jobject thiz, jint fd, jbyteArray buffer, jint length) {
    (void) thiz;

    struct pollfd pfd;
    pfd.fd = fd;
    pfd.events = POLLIN;
    pfd.revents = 0;

    int pr;
    do {
        pr = poll(&pfd, 1, KERN_POLL_TIMEOUT_MS);
    } while (pr < 0 && errno == EINTR);

    if (pr < 0) return KERN_READ_ERROR;
    if (pr == 0) return KERN_READ_TIMEOUT;

    jbyte *buf = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (buf == NULL) return KERN_READ_ERROR;

    ssize_t n;
    do {
        n = read(fd, buf, (size_t) length);
    } while (n < 0 && errno == EINTR);

    (*env)->ReleaseByteArrayElements(env, buffer, buf, 0);

    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) return KERN_READ_TIMEOUT;
        return KERN_READ_ERROR;
    }
    return (jint) n;
}

JNIEXPORT jint JNICALL
Java_com_aliedu_kern_core_terminal_pty_Pty_write(
        JNIEnv *env, jobject thiz, jint fd, jbyteArray buffer, jint length) {
    (void) thiz;

    if (fd < 0 || length <= 0) return 0;

    jbyte *buf = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (buf == NULL) return KERN_READ_ERROR;

    ssize_t total = 0;
    while (total < (ssize_t) length) {
        ssize_t n = write(fd, buf + total, (size_t) (length - total));
        if (n < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                struct pollfd pfd;
                pfd.fd = fd;
                pfd.events = POLLOUT;
                pfd.revents = 0;
                poll(&pfd, 1, KERN_POLL_TIMEOUT_MS);
                continue;
            }
            (*env)->ReleaseByteArrayElements(env, buffer, buf, JNI_ABORT);
            return KERN_READ_ERROR;
        }
        total += n;
    }

    (*env)->ReleaseByteArrayElements(env, buffer, buf, JNI_ABORT);
    return (jint) total;
}

JNIEXPORT jint JNICALL
Java_com_aliedu_kern_core_terminal_pty_Pty_waitFor(
        JNIEnv *env, jobject thiz, jint pid) {
    (void) env;
    (void) thiz;

    int status = 0;
    pid_t r;
    do {
        r = waitpid((pid_t) pid, &status, 0);
    } while (r < 0 && errno == EINTR);

    if (r < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

JNIEXPORT void JNICALL
Java_com_aliedu_kern_core_terminal_pty_Pty_hangupProcessGroup(
        JNIEnv *env, jobject thiz, jint pid) {
    (void) env;
    (void) thiz;
    if (pid > 0) kill(-((pid_t) pid), SIGHUP);
}

JNIEXPORT void JNICALL
Java_com_aliedu_kern_core_terminal_pty_Pty_killProcessGroup(
        JNIEnv *env, jobject thiz, jint pid, jint signal) {
    (void) env;
    (void) thiz;
    if (pid > 0) kill(-((pid_t) pid), (int) signal);
}

JNIEXPORT void JNICALL
Java_com_aliedu_kern_core_terminal_pty_Pty_close(
        JNIEnv *env, jobject thiz, jint fd) {
    (void) env;
    (void) thiz;
    if (fd >= 0) close(fd);
}
