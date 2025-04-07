#include <sys/param.h>
#include <sys/module.h>
#include <sys/cdefs.h>

#include <sys/param.h>
#include <sys/types.h>
#include <sys/kernel.h>
#include <sys/systm.h>
#include <sys/errno.h>
#include <sys/sysproto.h>
#include <sys/malloc.h>
#include <sys/osd.h>
#include <sys/priv.h>
#include <sys/proc.h>
#include <sys/epoch.h>
#include <sys/taskqueue.h>
#include <sys/fcntl.h>
#include <sys/jail.h>
#include <sys/linker.h>
#include <sys/lock.h>
#include <sys/mman.h>
#include <sys/mutex.h>
#include <sys/racct.h>
#include <sys/rctl.h>
#include <sys/refcount.h>
#include <sys/sx.h>
#include <sys/sysent.h>
#include <sys/namei.h>
#include <sys/mount.h>
#include <sys/queue.h>
#include <sys/socket.h>
#include <sys/syscallsubr.h>
#include <sys/sysctl.h>
#include <sys/uuid.h>
#include <sys/sbuf.h>

static int jail_mntinfo_modevent(module_t mod, int type, void *unused) {
    switch (type) {
        case MOD_LOAD:
            break;
        case MOD_UNLOAD:
            break;
        default:
            return EOPNOTSUPP;
    }
    return 0;
}

static void
escape_json(struct sbuf *sb, char *input)
{
        char *p = input;
        while (*p != 0) {
                switch (*p) {
                        case '"':
                        case '\\':
                                sbuf_putc(sb, '\\');
                                sbuf_putc(sb, *p);
                                break;
                        case '\b':
                                sbuf_cat(sb, "\\b");
                                break;
                        case '\f':
                                sbuf_cat(sb, "\\f");
                                break;
                        case '\n':
                                sbuf_cat(sb, "\\n");
                                break;
                        case '\r':
                                sbuf_cat(sb, "\\r");
                                break;
                        case '\t':
                                sbuf_cat(sb, "\\t");
                                break;
                        default:
                                sbuf_putc(sb, *p);
                }
                p++;
        }
}

static void
encode_hex(struct sbuf *sb, unsigned i)
{
        sbuf_printf(sb, "%02x", (i & 0xFF));
        sbuf_printf(sb, "%02x", ((i >> 8) & 0xFF));
        sbuf_printf(sb, "%02x", ((i >> 16) & 0xFF));
        sbuf_printf(sb, "%02x", ((i >> 24) & 0xFF));
}

static void
build_json(struct sbuf *sb, char *jpath, int enforce_statfs, struct thread *td)
{
        struct mount *mp;
        int jpathlen = strlen(jpath);
        int is_first = 1;

        sbuf_cat(sb, "{\"mounted\":[");

        mtx_lock(&mountlist_mtx);
        TAILQ_FOREACH(mp, &mountlist, mnt_list) {
                if (vfs_suser(mp, td) == 0) {
                    char *mntonname = mp->mnt_stat.f_mntonname;

                    if (enforce_statfs > 0) {
                            // remove jail path prefix
                            size_t len = strlen(mntonname);
                            if (jpathlen > 0 && len > jpathlen 
                                && mntonname[jpathlen] == '/' 
                                && strncmp(jpath, mntonname, jpathlen) == 0)
                            {
                                    mntonname = &mntonname[jpathlen];
                            }
                    }

                    if (is_first) {
                            is_first = 0;
                    } else {
                            sbuf_putc(sb, ',');
                    }

                    sbuf_cat(sb, "{\"fstype\":\"");
                    escape_json(sb, mp->mnt_stat.f_fstypename);
                    sbuf_cat(sb, "\",\"special\":\"");
                    escape_json(sb, mp->mnt_stat.f_mntfromname);
                    sbuf_cat(sb, "\",\"node\":\"");
                    escape_json(sb, mntonname);
                    sbuf_cat(sb, "\",\"fsid\":\"");
                    encode_hex(sb, (unsigned) mp->mnt_stat.f_fsid.val[0]);
                    encode_hex(sb, (unsigned) mp->mnt_stat.f_fsid.val[1]);
                    sbuf_cat(sb, "\"}");
                }
        }
        mtx_unlock(&mountlist_mtx);

        sbuf_cat(sb, "]}");
        sbuf_finish(sb);
}

static int 
sysctl_mntinfojson(SYSCTL_HANDLER_ARGS)
{
        char *jpath = "";
        int enforce_statfs = 0;
        static size_t hint = PAGE_SIZE;
        size_t len = 0;
        int error = 0;
        struct sbuf *sb;

        if (req->td == NULL || req->td->td_ucred == 0) {
                enforce_statfs = 2;
        } else if (req->td->td_ucred->cr_prison != NULL) {
                struct prison *p = req->td->td_ucred->cr_prison;
                jpath = p->pr_path;
                enforce_statfs = p->pr_enforce_statfs;
        }

        if (enforce_statfs > 1) {
                char *empty = "[]";
                sysctl_handle_string(oidp, empty, strlen(empty), req);
        }

        if (req->oldptr == NULL) {
                sb = sbuf_new(NULL, NULL, PAGE_SIZE, 
                              SBUF_FIXEDLEN | SBUF_INCLUDENUL);
                sbuf_set_drain(sb, sbuf_count_drain, &len);
                build_json(sb, jpath, enforce_statfs, req->td);
                req->oldidx = hint = len;
        } else {
                sb = sbuf_new(NULL, NULL, hint, 
                              SBUF_AUTOEXTEND | SBUF_INCLUDENUL);
                build_json(sb, jpath, enforce_statfs, req->td);
                hint = sbuf_len(sb);
                error = SYSCTL_OUT(req, sbuf_data(sb), sbuf_len(sb));
        }
        sbuf_delete(sb);
        return error;
}

SYSCTL_DECL(_security_jail);
SYSCTL_PROC(_security_jail, OID_AUTO, mntinfojson,
    CTLTYPE_STRING | CTLFLAG_RD | CTLFLAG_MPSAFE, 0, 0,
    sysctl_mntinfojson, "",
    "Get mount info as json");

static moduledata_t jail_mntinfo_mod = {
    "jail_mntinfo",
    jail_mntinfo_modevent,
    NULL
};

DECLARE_MODULE(jail_mntinfo, jail_mntinfo_mod, SI_SUB_KLD, SI_ORDER_ANY);
