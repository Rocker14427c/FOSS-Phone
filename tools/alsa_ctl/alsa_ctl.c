/* Minimal ALSA mixer control probe for Android stock kernels (no alsa-lib/alsa-utils on device).
 * Read-only by default; "set" writes an INTEGER/BOOLEAN/ENUMERATED value.
 * Usage:
 *   alsa_ctl [CARD] map              - list controls matching speech/mute/voice/ul_/dl_ (read-only)
 *   alsa_ctl [CARD] get  "NAME"      - read one control
 *   alsa_ctl [CARD] set  "NAME" V..  - write value(s) (explicit per-experiment approval required)
 * CARD is a number (default 0) -> /dev/snd/controlCn.
 */
#include <errno.h>
#include <fcntl.h>
#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sound/asound.h>

static const char *match_keys[] = { "speech", "mute", "voice", "ul_", "dl_", "mic", "vt_", "crs" };
#define N_MATCH (sizeof(match_keys)/sizeof(match_keys[0]))

static int name_matches(const char *n) {
    char low[sizeof(((struct snd_ctl_elem_id*)0)->name)];
    size_t i, l = strlen(n);
    if (l >= sizeof(low)) l = sizeof(low) - 1;
    for (i = 0; i < l; i++) low[i] = (char)tolower((unsigned char)n[i]);
    low[l] = 0;
    for (i = 0; i < N_MATCH; i++)
        if (strstr(low, match_keys[i])) return 1;
    return 0;
}

static const char *type_name(int t) {
    switch (t) {
        case SNDRV_CTL_ELEM_TYPE_BOOLEAN:    return "BOOL";
        case SNDRV_CTL_ELEM_TYPE_INTEGER:    return "INT";
        case SNDRV_CTL_ELEM_TYPE_ENUMERATED: return "ENUM";
        case SNDRV_CTL_ELEM_TYPE_BYTES:      return "BYTES";
        case SNDRV_CTL_ELEM_TYPE_IEC958:     return "IEC958";
        case SNDRV_CTL_ELEM_TYPE_INTEGER64:  return "INT64";
        default: return "?";
    }
}

static int find_by_name(int fd, const char *name, struct snd_ctl_elem_id *out) {
    struct snd_ctl_elem_list list;
    memset(&list, 0, sizeof(list));
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_LIST, &list) < 0) return -1;
    unsigned count = list.count;
    struct snd_ctl_elem_id *ids = calloc(count ? count : 1, sizeof(*ids));
    if (!ids) return -1;
    list.count = count;
    list.pids = ids;
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_LIST, &list) < 0) { free(ids); return -1; }
    int rc = -1;
    for (unsigned i = 0; i < list.used; i++) {
        if (strcmp((char*)ids[i].name, name) == 0) { *out = ids[i]; rc = 0; break; }
    }
    free(ids);
    return rc;
}

static void print_one(int fd, const struct snd_ctl_elem_id *id) {
    struct snd_ctl_elem_info info;
    struct snd_ctl_elem_value val;
    memset(&info, 0, sizeof(info)); info.id = *id;
    memset(&val, 0, sizeof(val));  val.id = *id;
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_INFO, &info) < 0) { printf("%-40s (info failed)\n", id->name); return; }
    printf("%-40s %-5s n=%-3u val:", id->name, type_name(info.type), info.count);
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_READ, &val) < 0) { printf(" (read failed)\n"); return; }
    if (info.type == SNDRV_CTL_ELEM_TYPE_ENUMERATED) {
        printf(" #%u/%u", val.value.enumerated.item[0], info.value.enumerated.items);
        info.value.enumerated.item = val.value.enumerated.item[0];
        if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_INFO, &info) == 0)
            printf(" \"%s\"", info.value.enumerated.name);
        printf("\n");
    } else {
        for (unsigned i = 0; i < info.count && i < 8; i++)
            printf(" %ld", val.value.integer.value[i]);
        printf("\n");
    }
}

static void do_map(int fd) {
    struct snd_ctl_elem_list list;
    memset(&list, 0, sizeof(list));
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_LIST, &list) < 0) { perror("ELEM_LIST"); exit(2); }
    struct snd_ctl_elem_id *ids = calloc(list.count ? list.count : 1, sizeof(*ids));
    if (!ids) { perror("calloc"); exit(2); }
    list.pids = ids;
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_LIST, &list) < 0) { perror("ELEM_LIST2"); exit(2); }
    for (unsigned i = 0; i < list.used; i++)
        if (name_matches((char*)ids[i].name))
            print_one(fd, &ids[i]);
    free(ids);
}

static void do_get(int fd, const char *name) {
    struct snd_ctl_elem_id id;
    if (find_by_name(fd, name, &id) < 0) { fprintf(stderr, "control not found: %s\n", name); exit(3); }
    print_one(fd, &id);
}

static void do_set(int fd, const char *name, int argc, char **argv) {
    struct snd_ctl_elem_id id;
    if (find_by_name(fd, name, &id) < 0) { fprintf(stderr, "control not found: %s\n", name); exit(3); }
    struct snd_ctl_elem_info info;
    struct snd_ctl_elem_value val;
    memset(&info, 0, sizeof(info)); info.id = id;
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_INFO, &info) < 0) { perror("ELEM_INFO"); exit(2); }
    if (argc < 1) { fprintf(stderr, "set needs at least one value\n"); exit(4); }
    memset(&val, 0, sizeof(val)); val.id = id;
    /* read current first so we can always print the before/after and report exact restore values */
    struct snd_ctl_elem_value before;
    memset(&before, 0, sizeof(before)); before.id = id;
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_READ, &before) == 0)
        printf("before: %ld\n", before.value.integer.value[0]);
    for (unsigned i = 0; i < info.count; i++) {
        long v = strtol(argv[0], NULL, 0);
        if ((int)i < argc) v = strtol(argv[i], NULL, 0);
        if (info.type == SNDRV_CTL_ELEM_TYPE_ENUMERATED)
            val.value.enumerated.item[i] = (unsigned)v;
        else
            val.value.integer.value[i] = v;
    }
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_WRITE, &val) < 0) { perror("ELEM_WRITE"); exit(2); }
    struct snd_ctl_elem_value after;
    memset(&after, 0, sizeof(after)); after.id = id;
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_READ, &after) == 0)
        printf("after:  %ld\n", after.value.integer.value[0]);
}

int main(int argc, char **argv) {
    int a = 1, card = 0;
    if (a < argc && isdigit((unsigned char)argv[a][0]) && strlen(argv[a]) <= 2)
        card = atoi(argv[a++]);
    if (a >= argc) { fprintf(stderr, "usage: alsa_ctl [card] map|get|set ...\n"); return 4; }
    char dev[64];
    snprintf(dev, sizeof(dev), "/dev/snd/controlC%d", card);
    int fd = open(dev, O_RDWR | O_CLOEXEC);
    if (fd < 0) { perror(dev); return 2; }
    const char *op = argv[a++];
    if (!strcmp(op, "map")) do_map(fd);
    else if (!strcmp(op, "get") && a < argc) do_get(fd, argv[a]);
    else if (!strcmp(op, "set") && a + 1 < argc) do_set(fd, argv[a], argc - a - 1, argv + a + 1);
    else { fprintf(stderr, "bad args\n"); return 4; }
    close(fd);
    return 0;
}
