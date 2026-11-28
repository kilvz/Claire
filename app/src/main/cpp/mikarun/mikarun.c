/*
 * mikarun — Claire sandbox launcher.
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) 2026 Mika authors
 *
 * Usage:
 *   libmikarun.so [--timeout <seconds,1-3600>] [--net on|off] [--nice <0-19,default 10>]
 *                 [--max-procs <default 256>] [--max-file-mb <default 2048>]
 *                 [--pty [--pty-cols <1-1000,default 80>] [--pty-rows <1-1000,default 24>]
 *                  [--pty-size <file>]] -- <program> [args…]
 *   libmikarun.so --self-test    prints "mikarun ok", "seccomp ok" / "seccomp unavailable: <reason>",
 *                                "pty ok" / "pty unavailable: <reason>", then exits 0
 *
 * Without --net it is treated as off (no network is the default). Without --timeout there is no time limit.
 * --pty connects the command to a pseudo-terminal (controlling terminal), for terminals the user opens; without it, a plain pipe is still used.
 * --pty-size points to a regular file whose content is "cols rows"; while running it is read every 200ms, and when it changes TIOCSWINSZ
 * resizes the window (the kernel sends SIGWINCH to the foreground process group).
 * Exit code: the command's own exit code; 124 on timeout; 128+N when the command is killed by signal N;
 *         125 when the launcher itself fails, with the reason on the last line of stderr, prefixed with "mikarun:".
 */

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <libgen.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>

#ifdef __ANDROID__
#include <android/api-level.h>
#endif

#define EXIT_TIMEOUT 124
#define EXIT_LAUNCHER_ERROR 125

/* aarch64 system call numbers (the filter only recognizes this architecture) */
#define NR_IOCTL_AARCH64 29
#define NR_SOCKET_AARCH64 198
#define NR_IO_URING_SETUP_AARCH64 425
#define NR_PIDFD_SEND_SIGNAL_AARCH64 424
#define NR_PIDFD_OPEN_AARCH64 434
#define NR_CLOSE_RANGE_AARCH64 436
#define BINDER_WRITE_READ_CMD 0xc0306201u /* _IOWR('b', 1, struct binder_write_read), 64-bit */

#define GRACE_MS 2000          /* how long to wait after SIGTERM before SIGKILL */
#define POLL_MS 50
#define KILL_ROUNDS_MAX 200    /* upper bound of the SIGKILL loop (about 10 seconds), so it never hangs in abnormal cases */
#define SCAN_MAX 32768

struct options {
	int self_test;
	long timeout_sec;  /* 0 = no time limit */
	int net_off;
	long nice;
	long max_procs;
	long max_file_mb;
	int pty;           /* 1 = give the command a pseudo-terminal */
	long pty_cols;     /* 1-1000, default 80 */
	long pty_rows;     /* 1-1000, default 24 */
	const char *pty_size_path; /* --pty-size: read "cols rows" every 200ms, change the window size when it changes */
	char **command; /* NULL-terminated, points inside argv */
};

static int fail(const char *what, const char *detail)
{
	if (detail != NULL)
		fprintf(stderr, "mikarun: %s: %s\n", what, detail);
	else
		fprintf(stderr, "mikarun: %s\n", what);
	return EXIT_LAUNCHER_ERROR;
}

static int parse_long(const char *name, const char *text, long min, long max, long *out)
{
	char *end;
	long v;

	if (text == NULL)
		return fail("missing value for", name), -1;
	errno = 0;
	v = strtol(text, &end, 10);
	if (errno != 0 || end == text || *end != '\0' || v < min || v > max) {
		fprintf(stderr, "mikarun: invalid value for %s: %s (allowed %ld-%ld)\n", name, text, min, max);
		return -1;
	}
	*out = v;
	return 0;
}

/* Returns 0 on success; on non-zero the error message has already been written. */
static int parse_options(int argc, char **argv, struct options *opts)
{
	int i = 1;

	memset(opts, 0, sizeof(*opts));
	opts->net_off = 1;
	opts->nice = 10;
	opts->max_procs = 256;
	opts->max_file_mb = 2048;
	opts->pty_cols = 80;
	opts->pty_rows = 24;
	for (; i < argc; i++) {
		const char *arg = argv[i];
		const char *value = i + 1 < argc ? argv[i + 1] : NULL;
		long *target = NULL;
		long min = 0, max = 0;

		if (strcmp(arg, "--") == 0) {
			i++;
			break;
		}
		if (strcmp(arg, "--self-test") == 0) {
			opts->self_test = 1;
			continue;
		}
		if (strcmp(arg, "--pty") == 0) {
			opts->pty = 1;
			continue;
		}
		if (strcmp(arg, "--net") == 0) {
			if (value != NULL && strcmp(value, "on") == 0)
				opts->net_off = 0;
			else if (value != NULL && strcmp(value, "off") == 0)
				opts->net_off = 1;
			else
				return fail("invalid value for --net (on|off)", value), -1;
			i++;
			continue;
		}
		if (strcmp(arg, "--pty-size") == 0) {
			if (value == NULL)
				return fail("missing value for --pty-size", NULL), -1;
			opts->pty = 1;
			opts->pty_size_path = value;
			i++;
			continue;
		}
		if (strcmp(arg, "--timeout") == 0) {
			target = &opts->timeout_sec; min = 1; max = 3600;
		} else if (strcmp(arg, "--nice") == 0) {
			target = &opts->nice; min = 0; max = 19;
		} else if (strcmp(arg, "--max-procs") == 0) {
			target = &opts->max_procs; min = 1; max = 65536;
		} else if (strcmp(arg, "--max-file-mb") == 0) {
			target = &opts->max_file_mb; min = 1; max = 1048576;
		} else if (strcmp(arg, "--pty-cols") == 0) {
			opts->pty = 1;
			target = &opts->pty_cols; min = 1; max = 1000;
		} else if (strcmp(arg, "--pty-rows") == 0) {
			opts->pty = 1;
			target = &opts->pty_rows; min = 1; max = 1000;
		}
		if (target != NULL) {
			if (parse_long(arg, value, min, max, target) != 0)
				return -1;
			i++;
			continue;
		}
		if (strncmp(arg, "--", 2) == 0)
			return fail("unknown option", arg), -1;
		break; /* the first non-option argument is the command */
	}

	if (opts->self_test)
		return 0;
	if (i >= argc)
		return fail("missing command", NULL), -1;
	opts->command = &argv[i];
	return 0;
}

/* ---------- network isolation: seccomp filter ---------- */

/*
 * Allowlist: socket() only allows AF_UNIX (the bridge needs it) and AF_NETLINK (querying local interfaces, routes, etc., which stays on the machine); other address families → EACCES;
 * io_uring_setup → ENOSYS (it can bypass the socket filter); binder's BINDER_WRITE_READ ioctl → EACCES
 * (data can also be carried out through system services, and PRoot does not bind the binder device anyway, so this is one more layer); everything else is allowed.
 * Non-aarch64 system calls (32-bit compatibility layer) always kill the process, so another call number cannot be used to bypass it. The filter is inherited across fork/exec and cannot be removed.
 */
static int install_net_filter(char *reason, size_t reason_len)
{
	struct sock_filter filter[] = {
		/* 0 */ BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
		/* 1 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
		/* 2 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
		/* 3 */ BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
		/* 4 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, NR_IO_URING_SETUP_AARCH64, 0, 1),
		/* 5 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | (ENOSYS & SECCOMP_RET_DATA)),
		/* 6 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, NR_SOCKET_AARCH64, 2, 0),          /* → 9 */
		/* 7 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, NR_IOCTL_AARCH64, 6, 0),           /* → 14 */
		/* 8 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
		/* little-endian: the low 32 bits of an argument come first; socket's domain and ioctl's cmd are 32-bit, and the kernel only looks at the low 32 bits */
		/* 9 */ BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
		/* 10 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AF_UNIX, 2, 0),                  /* → 13 */
		/* 11 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AF_NETLINK, 1, 0),               /* → 13 */
		/* 12 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | (EACCES & SECCOMP_RET_DATA)),
		/* 13 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
		/* 14 */ BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[1])),
		/* 15 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, BINDER_WRITE_READ_CMD, 0, 1),
		/* 16 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | (EACCES & SECCOMP_RET_DATA)),
		/* 17 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
	};
	struct sock_fprog prog = {
		.len = (unsigned short)(sizeof(filter) / sizeof(filter[0])),
		.filter = filter,
	};

	if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
		snprintf(reason, reason_len, "PR_SET_NO_NEW_PRIVS: %s", strerror(errno));
		return -1;
	}
	if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog, 0, 0) != 0) {
		snprintf(reason, reason_len, "PR_SET_SECCOMP: %s", strerror(errno));
		return -1;
	}
	return 0;
}

/* Install the filter in a child process and test it for real: AF_INET is denied, AF_UNIX works. Returns 0 when usable, otherwise reason holds the reason. */
static int probe_net_filter(char *reason, size_t reason_len)
{
	int fds[2];
	pid_t pid;
	int status;
	ssize_t n;

	if (pipe(fds) != 0) {
		snprintf(reason, reason_len, "pipe: %s", strerror(errno));
		return -1;
	}
	pid = fork();
	if (pid < 0) {
		snprintf(reason, reason_len, "fork: %s", strerror(errno));
		close(fds[0]);
		close(fds[1]);
		return -1;
	}
	if (pid == 0) {
		char msg[128] = "";
		int s;

		close(fds[0]);
		if (install_net_filter(msg, sizeof(msg)) == 0) {
			s = socket(AF_INET, SOCK_STREAM, 0);
			if (s >= 0)
				snprintf(msg, sizeof(msg), "filter installed but AF_INET socket still allowed");
			else if (errno != EACCES)
				snprintf(msg, sizeof(msg), "AF_INET socket failed with %s, expected EACCES", strerror(errno));
			else if ((s = socket(AF_UNIX, SOCK_STREAM, 0)) < 0)
				snprintf(msg, sizeof(msg), "AF_UNIX socket blocked: %s", strerror(errno));
		}
		if (msg[0] != '\0' && write(fds[1], msg, strlen(msg)) < 0)
			_exit(2);
		_exit(msg[0] == '\0' ? 0 : 1);
	}
	close(fds[1]);
	n = read(fds[0], reason, reason_len - 1);
	close(fds[0]);
	reason[n > 0 ? n : 0] = '\0';
	while (waitpid(pid, &status, 0) < 0 && errno == EINTR)
		;
	if (WIFSIGNALED(status)) {
		snprintf(reason, reason_len, "probe killed by signal %d", WTERMSIG(status));
		return -1;
	}
	if (!WIFEXITED(status) || WEXITSTATUS(status) != 0) {
		if (reason[0] == '\0')
			snprintf(reason, reason_len, "probe failed");
		return -1;
	}
	return 0;
}

static int pty_self_test(char *reason, size_t reason_len);

static int self_test(void)
{
	char reason[160];

	if (printf("mikarun ok\n") < 0 || fflush(stdout) != 0)
		return fail("self-test", "cannot write stdout");
	if (probe_net_filter(reason, sizeof(reason)) == 0)
		printf("seccomp ok\n");
	else
		printf("seccomp unavailable: %s\n", reason);
	if (pty_self_test(reason, sizeof(reason)) == 0)
		printf("pty ok\n");
	else
		printf("pty unavailable: %s\n", reason);
	if (fflush(stdout) != 0)
		return fail("self-test", "cannot write stdout");
	return 0;
}

/* ---------- /proc scanning ---------- */

/*
 * The seccomp policy Android installs for an app sends SIGSYS (killing the process) for system calls it does not know, instead of returning ENOSYS.
 * pidfd_open/pidfd_send_signal are only in the policy from API 31, close_range from API 34, so they must not be tried on earlier systems.
 */
static int api_level(void)
{
#ifdef __ANDROID__
	return android_get_device_api_level();
#elif defined(MIKARUN_HOST_API_LEVEL)
	return MIKARUN_HOST_API_LEVEL; /* only used to test the fallback on ordinary Linux */
#else
	return 10000;
#endif
}

/* Read the parent process number and thread count from /proc/<pid>/stat. Returns 0 on success. */
static int read_stat(int proc_fd, const char *pid_name, pid_t *ppid, long *threads)
{
	char path[64], buf[1024];
	const char *p;
	ssize_t n;
	int fd, pp;
	long nt;
	char state;

	snprintf(path, sizeof(path), "%s/stat", pid_name);
	fd = openat(proc_fd, path, O_RDONLY | O_CLOEXEC);
	if (fd < 0)
		return -1;
	n = read(fd, buf, sizeof(buf) - 1);
	close(fd);
	if (n <= 0)
		return -1;
	buf[n] = '\0';
	p = strrchr(buf, ')'); /* the process name may contain spaces and parentheses, so read from the last ')' onward */
	if (p == NULL)
		return -1;
	/* fields 3 state, 4 ppid, ..., 20 num_threads */
	if (sscanf(p + 2, "%c %d %*d %*d %*d %*d %*u %*u %*u %*u %*u %*u %*u %*d %*d %*d %*d %ld",
		   &state, &pp, &nt) != 3)
		return -1;
	*ppid = pp;
	*threads = nt;
	return 0;
}

static int is_pid_name(const char *s)
{
	if (*s == '\0')
		return 0;
	for (; *s != '\0'; s++)
		if (*s < '0' || *s > '9')
			return 0;
	return 1;
}

/* Current number of tasks for this identity (uid); threads count too, since RLIMIT_NPROC counts tasks. */
static long count_user_tasks(void)
{
	DIR *dir = opendir("/proc");
	struct dirent *e;
	uid_t uid = getuid();
	long total = 0;

	if (dir == NULL)
		return -1;
	while ((e = readdir(dir)) != NULL) {
		struct stat st;
		pid_t ppid;
		long threads;

		if (!is_pid_name(e->d_name))
			continue;
		if (fstatat(dirfd(dir), e->d_name, &st, 0) != 0 || st.st_uid != uid)
			continue;
		if (read_stat(dirfd(dir), e->d_name, &ppid, &threads) == 0)
			total += threads;
	}
	closedir(dir);
	return total;
}

static pid_t scan_pid[SCAN_MAX];
static pid_t scan_ppid[SCAN_MAX];
static unsigned char scan_mark[SCAN_MAX];
static int scan_count;
static int pidfd_usable = -1; /* -1 unknown, 0 unavailable, 1 usable */

/* Read /proc/<pid>/stat again: the parent is still this process, or a descendant recognized by this scan round. */
static int still_descendant(pid_t pid)
{
	char name[32];
	pid_t ppid;
	long threads;
	int i;

	snprintf(name, sizeof(name), "/proc/%d", (int)pid);
	if (read_stat(AT_FDCWD, name, &ppid, &threads) != 0)
		return 0;
	if (ppid == getpid())
		return 1;
	for (i = 0; i < scan_count; i++)
		if (scan_mark[i] && scan_pid[i] == ppid)
			return 1;
	return 0;
}

/*
 * Between the scan and sending a signal, the process number may already have been reaped and reused for another process of the same identity (Claire itself).
 * With pidfd: first pin the process identity with pidfd_open, then confirm it is still a descendant, then send the signal through the pidfd — no wrong kill.
 * Without pidfd the only option is to confirm once more before kill (a very small window); kill_tree also SIGSTOPs the whole tree first and rescans,
 * and a stopped process does not reap children, so the process numbers from the rescan stay stable.
 */
static void send_to_descendant(pid_t pid, int sig)
{
	if (pidfd_usable < 0)
		pidfd_usable = api_level() >= 31;
	if (pidfd_usable) {
		int fd = (int)syscall(NR_PIDFD_OPEN_AARCH64, pid, 0);
		if (fd >= 0) {
			if (still_descendant(pid))
				syscall(NR_PIDFD_SEND_SIGNAL_AARCH64, fd, sig, NULL, 0);
			close(fd);
			return;
		}
		if (errno == ESRCH)
			return;
		pidfd_usable = 0; /* kernel too old (ENOSYS) etc.: use the fallback below */
	}
	if (still_descendant(pid))
		kill(pid, sig);
}

/*
 * Find all processes whose parent chain eventually points to this process (this process is the child subreaper, so descendants that left their parent hang back here too);
 * when sig is non-zero, send them that signal. Returns the number found (including zombies not yet reaped); when the scan table is full one extra is counted, treated as "there are more".
 */
static int signal_descendants(int sig)
{
	DIR *dir = opendir("/proc");
	struct dirent *e;
	pid_t self = getpid();
	int n = 0, found = 0, full, changed, i;

	if (dir == NULL)
		return 1; /* if it cannot be seen, assume there are more and let the caller keep waiting */
	while ((e = readdir(dir)) != NULL && n < SCAN_MAX) {
		pid_t ppid;
		long threads;

		if (!is_pid_name(e->d_name))
			continue;
		if (read_stat(dirfd(dir), e->d_name, &ppid, &threads) != 0)
			continue;
		scan_pid[n] = (pid_t)atoi(e->d_name);
		scan_ppid[n] = ppid;
		scan_mark[n] = ppid == self;
		n++;
	}
	full = e != NULL; /* full before the read finished */
	closedir(dir);

	do {
		changed = 0;
		for (i = 0; i < n; i++) {
			int j;
			if (scan_mark[i])
				continue;
			for (j = 0; j < n; j++) {
				if (scan_mark[j] && scan_pid[j] == scan_ppid[i]) {
					scan_mark[i] = 1;
					changed = 1;
					break;
				}
			}
		}
	} while (changed);

	scan_count = n;
	for (i = 0; i < n; i++) {
		if (!scan_mark[i])
			continue;
		found++;
		if (sig != 0)
			send_to_descendant(scan_pid[i], sig);
	}
	return found + full;
}

/* ---------- running and cleanup ---------- */

/* Reap every finished direct child (including orphans that hung back); the main child's status is recorded. */
static void reap(pid_t child, int *status, int *have_status)
{
	pid_t p;
	int st;

	while ((p = waitpid(-1, &st, WNOHANG)) > 0) {
		if (p == child) {
			*status = st;
			*have_status = 1;
		}
	}
}

static void sleep_ms(long ms)
{
	struct timespec ts = { ms / 1000, (ms % 1000) * 1000000L };
	while (nanosleep(&ts, &ts) != 0 && errno == EINTR)
		;
}

/* First send SIGTERM to the process group and all descendants and wait GRACE_MS; SIGKILL the descendants still there, looping until none remain. */
static void kill_tree(pid_t child, int *status, int *have_status)
{
	long waited;
	int round;

	reap(child, status, have_status);
	if (signal_descendants(0) == 0)
		return;
	kill(-getpid(), SIGTERM); /* this process is in the group too; SIGTERM is already blocked, so it does not affect us */
	signal_descendants(SIGTERM);
	for (waited = 0; waited < GRACE_MS; waited += POLL_MS) {
		sleep_ms(POLL_MS);
		reap(child, status, have_status);
		if (signal_descendants(0) == 0)
			return;
	}
	for (round = 0; round < KILL_ROUNDS_MAX; round++) {
		if (!pidfd_usable)
			signal_descendants(SIGSTOP); /* freeze the whole tree first, so the process numbers from the rescan below no longer change */
		if (signal_descendants(SIGKILL) == 0)
			return;
		sleep_ms(POLL_MS);
		reap(child, status, have_status);
	}
	fprintf(stderr, "mikarun: warning: descendants still present after SIGKILL\n");
}

static long now_ms(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

/*
 * When PROOT_LOADER is not set, PRoot writes the embedded loader into a temporary directory and executes it (Android forbids executing files in the data directory),
 * so before starting libproot.so, PROOT_LOADER must point to a file in the same directory as the launcher (nativeLibraryDir).
 * Returns 0 when it passes; on non-zero the error message has already been written.
 */
static int check_proot_loader(const char *program)
{
	char path[PATH_MAX], self[PATH_MAX], loader[PATH_MAX], self_dir[PATH_MAX];
	const char *env = getenv("PROOT_LOADER");
	ssize_t n;

	snprintf(path, sizeof(path), "%s", program);
	if (strcmp(basename(path), "libproot.so") != 0)
		return 0;
	if (env == NULL || env[0] == '\0' || realpath(env, loader) == NULL)
		return fail("PROOT_LOADER missing", env);
	n = readlink("/proc/self/exe", self, sizeof(self) - 1);
	if (n < 0)
		return fail("cannot read /proc/self/exe", strerror(errno));
	self[n] = '\0';
	/* bionic's dirname returns a shared buffer, so copy it out first */
	snprintf(self_dir, sizeof(self_dir), "%s", dirname(self));
	snprintf(path, sizeof(path), "%s", loader);
	if (strcmp(basename(path), "libproot_loader.so") != 0)
		return fail("PROOT_LOADER missing", "not libproot_loader.so");
	if (strcmp(self_dir, dirname(loader)) != 0)
		return fail("PROOT_LOADER missing", "not in the launcher directory");
	return 0;
}

/* File size and process count limits; set the soft and hard limits together, and never above the existing hard limit. */
static int set_limit(int resource, rlim_t value, const char *name)
{
	struct rlimit rl;

	if (getrlimit(resource, &rl) != 0)
		return fail(name, strerror(errno));
	if (rl.rlim_max != RLIM_INFINITY && value > rl.rlim_max)
		value = rl.rlim_max;
	rl.rlim_cur = value;
	rl.rlim_max = value;
	if (setrlimit(resource, &rl) != 0)
		return fail(name, strerror(errno));
	return 0;
}

/* oom_score_adj can only be raised (ordinary identity); if it cannot be written, so be it. */
static void set_oom_score_adj(const char *value)
{
	int fd = open("/proc/self/oom_score_adj", O_WRONLY | O_CLOEXEC);
	if (fd >= 0) {
		ssize_t ignored = write(fd, value, strlen(value));
		(void)ignored;
		close(fd);
	}
}

/* Close inherited descriptors other than 0/1/2, so the command cannot get any of Claire's files or sockets. */
static void close_inherited_fds(void)
{
	DIR *dir;
	struct dirent *e;

	if (api_level() >= 34 && syscall(NR_CLOSE_RANGE_AARCH64, 3, ~0U, 0) == 0)
		return;
	dir = opendir("/proc/self/fd");
	if (dir == NULL)
		return;
	while ((e = readdir(dir)) != NULL) {
		int fd;
		if (!is_pid_name(e->d_name))
			continue;
		fd = atoi(e->d_name);
		if (fd >= 3 && fd != dirfd(dir))
			close(fd);
	}
	closedir(dir);
}

/* ---------- pseudo-terminal ---------- */

/* Write all [len] bytes; keep writing if interrupted by a signal. Returns 0 on success. */
static int write_all(int fd, const char *buf, size_t len)
{
	while (len > 0) {
		ssize_t n = write(fd, buf, len);

		if (n < 0) {
			if (errno == EINTR)
				continue;
			return -1;
		}
		buf += n;
		len -= (size_t)n;
	}
	return 0;
}

/* Create a pty pair; [cols]/[rows] are already clamped to 1-1000. On failure reason holds the reason. */
static int open_pty(int *master, int *slave, long cols, long rows, char *reason, size_t reason_len)
{
	struct winsize ws;
	char *name;
	int m, s;

	m = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
	if (m < 0) {
		snprintf(reason, reason_len, "posix_openpt: %s", strerror(errno));
		return -1;
	}
	if (grantpt(m) != 0) {
		snprintf(reason, reason_len, "grantpt: %s", strerror(errno));
		close(m);
		return -1;
	}
	if (unlockpt(m) != 0) {
		snprintf(reason, reason_len, "unlockpt: %s", strerror(errno));
		close(m);
		return -1;
	}
	name = ptsname(m);
	if (name == NULL) {
		snprintf(reason, reason_len, "ptsname: %s", strerror(errno));
		close(m);
		return -1;
	}
	s = open(name, O_RDWR | O_NOCTTY | O_CLOEXEC);
	if (s < 0) {
		snprintf(reason, reason_len, "open %s: %s", name, strerror(errno));
		close(m);
		return -1;
	}
	memset(&ws, 0, sizeof(ws));
	ws.ws_col = (unsigned short)cols;
	ws.ws_row = (unsigned short)rows;
	if (ioctl(s, TIOCSWINSZ, &ws) != 0) {
		snprintf(reason, reason_len, "TIOCSWINSZ: %s", strerror(errno));
		close(s);
		close(m);
		return -1;
	}
	*master = m;
	*slave = s;
	return 0;
}

static int pty_self_test(char *reason, size_t reason_len)
{
	int master = -1, slave = -1;

	if (open_pty(&master, &slave, 80, 24, reason, reason_len) != 0)
		return -1;
	if (!isatty(slave)) {
		snprintf(reason, reason_len, "slave is not a tty");
		close(slave);
		close(master);
		return -1;
	}
	close(slave);
	close(master);
	return 0;
}

/*
 * Read the --pty-size file ("cols rows"); when it changes, ioctl(TIOCSWINSZ), and the kernel sends SIGWINCH to the foreground process group.
 * When the file does not exist, the content is incomplete or a value is out of range, do nothing and try again next round.
 */
static void apply_pty_size(int master, const char *path, long *cols, long *rows)
{
	char buf[64];
	struct winsize ws;
	long c, r;
	ssize_t n;
	int fd;

	if (path == NULL || *path == '\0')
		return;
	fd = open(path, O_RDONLY | O_CLOEXEC);
	if (fd < 0)
		return;
	n = read(fd, buf, sizeof(buf) - 1);
	close(fd);
	if (n <= 0)
		return;
	buf[n] = '\0';
	if (sscanf(buf, "%ld %ld", &c, &r) != 2)
		return;
	if (c < 1 || c > 1000 || r < 1 || r > 1000 || (c == *cols && r == *rows))
		return;
	memset(&ws, 0, sizeof(ws));
	ws.ws_col = (unsigned short)c;
	ws.ws_row = (unsigned short)r;
	if (ioctl(master, TIOCSWINSZ, &ws) == 0) {
		*cols = c;
		*rows = r;
	}
}

/*
 * Wait loop in pty mode: forward stdin to the master and the master's output to stdout, while watching the child and signals.
 * After the child exits, on timeout or when interrupted by SIGTERM, drain the remaining bytes from the slave (at most 200 milliseconds) so the last line is not lost.
 */
static void relay_pty(int master, pid_t child, const struct options *opts,
	int *status, int *have_status, int *timed_out, int *terminated, sigset_t *wait_set)
{
	char buf[8192];
	int master_open = 1;
	int stdin_open = 1;
	long deadline = 0;
	long last_cols = opts->pty_cols;
	long last_rows = opts->pty_rows;
	long last_size_check = 0;

	if (opts->timeout_sec > 0)
		deadline = now_ms() + opts->timeout_sec * 1000L;

	for (;;) {
		struct pollfd fds[2];
		struct timespec zero = { 0, 0 };
		int timeout = POLL_MS;
		int pr, i, sig;
		long now = now_ms();

		if (opts->pty_size_path != NULL && now - last_size_check >= 200) {
			apply_pty_size(master, opts->pty_size_path, &last_cols, &last_rows);
			last_size_check = now;
		}
		reap(child, status, have_status);
		if (*have_status)
			break;
		if (deadline != 0) {
			long left = deadline - now_ms();

			if (left <= 0) {
				*timed_out = 1;
				break;
			}
			if (left < timeout)
				timeout = (int)left;
		}

		fds[0].fd = master_open ? master : -1;
		fds[0].events = POLLIN;
		fds[0].revents = 0;
		fds[1].fd = stdin_open ? STDIN_FILENO : -1;
		fds[1].events = POLLIN;
		fds[1].revents = 0;
		pr = poll(fds, 2, timeout);
		if (pr < 0 && errno != EINTR)
			break;
		if (pr > 0) {
			for (i = 0; i < 2; i++) {
				ssize_t n;

				if (fds[i].fd < 0 || fds[i].revents == 0)
					continue;
				if (fds[i].fd == master) {
					n = read(master, buf, sizeof(buf));
					if (n > 0) {
						if (write_all(STDOUT_FILENO, buf, (size_t)n) != 0)
							master_open = 0;
					} else if (n == 0 || (errno != EINTR && errno != EAGAIN)) {
						master_open = 0; /* EIO: the child closed the slave */
					}
				} else {
					n = read(STDIN_FILENO, buf, sizeof(buf));
					if (n > 0) {
						if (write_all(master, buf, (size_t)n) != 0)
							stdin_open = 0;
					} else if (n == 0 || (errno != EINTR && errno != EAGAIN)) {
						stdin_open = 0;
						/* stdin closed: give the shell an EOF so it exits by itself; if the slave is still open, output can still be received */
						if (master_open && write_all(master, "\004", 1) != 0)
							master_open = 0;
					}
				}
			}
		}
		/* Non-blocking peek for a signal to handle; SIGCHLD is reaped next round */
		sig = sigtimedwait(wait_set, NULL, &zero);
		if (sig == SIGTERM || sig == SIGINT || sig == SIGHUP) {
			*terminated = sig;
			break;
		}
	}

	while (master_open) {
		struct pollfd pfd;
		ssize_t n;

		pfd.fd = master;
		pfd.events = POLLIN;
		pfd.revents = 0;
		if (poll(&pfd, 1, 200) <= 0)
			break;
		n = read(master, buf, sizeof(buf));
		if (n <= 0)
			break;
		if (write_all(STDOUT_FILENO, buf, (size_t)n) != 0)
			break;
	}
}

/* Own session, subreaper, parent-death signal, priority, OOM score, resource limits, network isolation. Returns 0 on success. */
static int prepare(const struct options *opts)
{
	pid_t parent = getppid();
	char reason[160];
	long used;

	/* The parent was gone before PPID was read: already adopted by init, so the parent-death signal will never come */
	if (parent == 1)
		return fail("parent exited", NULL);
	if (prctl(PR_SET_PDEATHSIG, SIGTERM, 0, 0, 0) != 0)
		return fail("PR_SET_PDEATHSIG", strerror(errno));
	if (getppid() != parent) /* the parent was already gone before it was set */
		return fail("parent exited", NULL);
	if (setsid() < 0 && getsid(0) != getpid())
		return fail("setsid", strerror(errno));
	if (prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) != 0)
		return fail("PR_SET_CHILD_SUBREAPER", strerror(errno));

	/*
	 * Priority and OOM score only protect Claire itself; they cannot stop the command from being killed. The launcher is 900, the command 1000 (adjusted again in the child):
	 * when memory runs short the command is killed first, and the launcher must stay alive to clean up.
	 */
	setpriority(PRIO_PROCESS, 0, (int)opts->nice);
	set_oom_score_adj("900");

	if (set_limit(RLIMIT_FSIZE, (rlim_t)opts->max_file_mb << 20, "RLIMIT_FSIZE") != 0)
		return -1;
	/*
	 * RLIMIT_NPROC is counted by the kernel per identity (uid) as a whole: Claire's threads and other sandbox commands running at the same time all draw on the same quota,
	 * so the limit = tasks this identity already had when set + max-procs, not max-procs for this command alone.
	 */
	used = count_user_tasks();
	if (used < 0)
		return fail("cannot count processes", strerror(errno));
	if (set_limit(RLIMIT_NPROC, (rlim_t)(used + opts->max_procs), "RLIMIT_NPROC") != 0)
		return -1;

	if (opts->net_off && install_net_filter(reason, sizeof(reason)) != 0)
		return fail("network isolation unavailable", reason);
	return 0;
}

static int run(const struct options *opts)
{
	sigset_t wait_set, orig_set;
	int status = 0, have_status = 0, timed_out = 0, terminated = 0;
	int pty_master = -1, pty_slave = -1;
	char pty_reason[160];
	long deadline = 0;
	pid_t child;

	if (check_proot_loader(opts->command[0]) != 0)
		return EXIT_LAUNCHER_ERROR;
	/* Block the signals to wait for first, then do the rest, so a signal cannot arrive and get lost before sigtimedwait */
	sigemptyset(&wait_set);
	sigaddset(&wait_set, SIGCHLD);
	sigaddset(&wait_set, SIGTERM);
	sigaddset(&wait_set, SIGINT);
	sigaddset(&wait_set, SIGHUP);
	if (sigprocmask(SIG_BLOCK, &wait_set, &orig_set) != 0)
		return fail("sigprocmask", strerror(errno));
	if (prepare(opts) != 0)
		return EXIT_LAUNCHER_ERROR;
	if (opts->pty && open_pty(&pty_master, &pty_slave, opts->pty_cols, opts->pty_rows, pty_reason, sizeof(pty_reason)) != 0)
		return fail("pty", pty_reason);

	child = fork();
	if (child < 0) {
		if (opts->pty) {
			close(pty_slave);
			close(pty_master);
		}
		return fail("fork", strerror(errno));
	}
	if (child == 0) {
		sigprocmask(SIG_SETMASK, &orig_set, NULL);
		/*
		 * When the launcher is SIGKILLed directly it has no chance to clean up: PRoot dies along via PDEATHSIG, and PRoot is patched with
		 * PTRACE_O_EXITKILL, so once it dies all traced processes are killed by the kernel. When Claire's whole process is killed by the system,
		 * Android kills the processes in the same cgroup together per the app's cgroup (including all sandbox processes here); this is the last line of defense.
		 */
		prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0);
		set_oom_score_adj("1000");
		if (opts->pty) {
			/*
			 * The parent already called setsid() in prepare(); the child creates another session so the slave can be made the controlling terminal.
			 * The slave was opened before fork, so this does not go through the filesystem again.
			 */
			if (setsid() < 0 && getsid(0) != getpid()) {
				fprintf(stderr, "mikarun: pty: setsid: %s\n", strerror(errno));
				_exit(EXIT_LAUNCHER_ERROR);
			}
			if (ioctl(pty_slave, TIOCSCTTY, 0) != 0) {
				fprintf(stderr, "mikarun: pty: TIOCSCTTY: %s\n", strerror(errno));
				_exit(EXIT_LAUNCHER_ERROR);
			}
			if (dup2(pty_slave, STDIN_FILENO) < 0 || dup2(pty_slave, STDOUT_FILENO) < 0 ||
			    dup2(pty_slave, STDERR_FILENO) < 0) {
				fprintf(stderr, "mikarun: pty: dup2: %s\n", strerror(errno));
				_exit(EXIT_LAUNCHER_ERROR);
			}
			close(pty_slave);
			close(pty_master);
		}
		close_inherited_fds();
		execv(opts->command[0], opts->command);
		fprintf(stderr, "mikarun: exec failed: %s\n", strerror(errno));
		_exit(EXIT_LAUNCHER_ERROR);
	}

	if (opts->pty) {
		close(pty_slave);
		relay_pty(pty_master, child, opts, &status, &have_status, &timed_out, &terminated, &wait_set);
		close(pty_master);
	} else {
		if (opts->timeout_sec > 0)
			deadline = now_ms() + opts->timeout_sec * 1000L;
		for (;;) {
			struct timespec ts, *tsp = NULL;
			int sig;

			reap(child, &status, &have_status);
			if (have_status)
				break;
			if (deadline != 0) {
				long left = deadline - now_ms();
				if (left <= 0) {
					timed_out = 1;
					break;
				}
				ts.tv_sec = left / 1000;
				ts.tv_nsec = (left % 1000) * 1000000L;
				tsp = &ts;
			}
			sig = sigtimedwait(&wait_set, NULL, tsp);
			if (sig == SIGTERM || sig == SIGINT || sig == SIGHUP) {
				terminated = sig;
				break;
			}
			/* SIGCHLD, timeout (EAGAIN), EINTR: go back to the top of the loop and check again */
		}
	}

	/* Clean up on normal command exit too: processes left in the background must not outlive this command */
	kill_tree(child, &status, &have_status);
	if (timed_out)
		return EXIT_TIMEOUT;
	if (have_status) {
		if (WIFEXITED(status))
			return WEXITSTATUS(status);
		if (WIFSIGNALED(status))
			return 128 + WTERMSIG(status);
	}
	return 128 + terminated;
}

int main(int argc, char **argv)
{
	struct options opts;

	if (parse_options(argc, argv, &opts) != 0)
		return EXIT_LAUNCHER_ERROR;
	if (opts.self_test)
		return self_test();
	return run(&opts);
}
