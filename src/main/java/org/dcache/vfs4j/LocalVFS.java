package org.dcache.vfs4j;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.security.auth.Subject;

import jnr.ffi.provider.FFIProvider;
import jnr.constants.platform.Errno;
import jnr.ffi.Address;
import jnr.ffi.annotations.*;
import org.dcache.nfs.status.*;

import org.dcache.auth.Subjects;
import org.dcache.nfs.v4.NfsIdMapping;
import org.dcache.nfs.v4.SimpleIdMap;
import org.dcache.nfs.v4.xdr.nfsace4;
import org.dcache.nfs.vfs.AclCheckable;
import org.dcache.nfs.vfs.DirectoryEntry;
import org.dcache.nfs.vfs.FsStat;
import org.dcache.nfs.vfs.Inode;
import org.dcache.nfs.vfs.Stat;
import org.dcache.nfs.vfs.VirtualFileSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 */
public class LocalVFS implements VirtualFileSystem {

    private final Logger LOG = LoggerFactory.getLogger(LocalVFS.class);

    // stolen from /usr/include/bits/fcntl-linux.h
    private final static int O_DIRECTORY = 0200000;
    private final static int O_RDONLY = 00;
    private final static int O_PATH = 010000000;
    private final static int O_NOFOLLOW	= 0400000;

    private final static int AT_REMOVEDIR = 0x200;
    private final static int AT_EMPTY_PATH = 0x1000;

    private final static int NONE = 0;

    private final SysVfs sysVfs;
    private final jnr.ffi.Runtime runtime;

    private final File root;
    private final KernelFileHandle rootFh;
    private final int rootFd;
    private final int mountId;

    private final NfsIdMapping idMapper = new SimpleIdMap();

    public LocalVFS(File root) throws IOException {
        this.root = root;

        sysVfs = FFIProvider.getSystemProvider()
                .createLibraryLoader(SysVfs.class)
                .load("c");
        runtime = jnr.ffi.Runtime.getRuntime(sysVfs);

        rootFd = sysVfs.open(root.getAbsolutePath(), O_DIRECTORY, NONE);
        checkError(rootFd >= 0);
        rootFh = new KernelFileHandle(runtime);

        int[] mntId = new int[1];
        int rc = sysVfs.name_to_handle_at(rootFd, "", rootFh, mntId, AT_EMPTY_PATH);
        checkError(rc == 0);
        mountId = mntId[0];

        LOG.debug("handle  =  {}, mountid = {}", rootFh, mountId);

    }

    @Override
    public int access(Inode inode, int mode) throws IOException {
        // pseudofs will do the checks
        return mode;
    }

    @Override
    public Inode create(Inode parent, Stat.Type type, String path, Subject subject, int mode) throws IOException {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public FsStat getFsStat() throws IOException {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public Inode getRootInode() throws IOException {
        return toInode(rootFh);
    }

    @Override
    public Inode lookup(Inode parent, String path) throws IOException {
        try(RawFd fd = inode2fd(parent, O_DIRECTORY | O_PATH )) {
            return toInode(path2fh(fd.fd(), path, 0));
        }
    }

    @Override
    public Inode link(Inode parent, Inode link, String path, Subject subject) throws IOException {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public List<DirectoryEntry> list(Inode inode) throws IOException {

        List<DirectoryEntry> list = new ArrayList<>();
        try (RawFd fd = inode2fd(inode, O_DIRECTORY)) {
            Address p = sysVfs.fdopendir(fd.fd());
            checkError(p != null);

            while (true) {
                Dirent dirent = sysVfs.readdir(p);
//            checkError(dirent != null);

                if (dirent == null) {
                    break;
                }

                byte[] b = new byte[255];
                int i = 0;
                for (; dirent.d_name[i].get() != '\0'; i++) {
                    b[i] = dirent.d_name[i].get();
                }
                String name = new String(b, 0, i, UTF_8);
                Inode fInode = lookup(inode, name);
                Stat stat = getattr(fInode);
                list.add(new DirectoryEntry(name, fInode, stat));

            }
         //   int rc = sysVfs.closedir(p);
         //   checkError(rc == 0);
        }
        return list;
    }

    @Override
    public Inode mkdir(Inode parent, String path, Subject subject, int mode) throws IOException {
        int uid = (int) Subjects.getUid(subject);
        int gid = (int) Subjects.getPrimaryGid(subject);

        Inode inode;
        try (RawFd fd = inode2fd(parent, O_PATH | O_NOFOLLOW | O_DIRECTORY)) {
            int rc = sysVfs.mkdirat(fd.fd(), path, mode);
            checkError(rc == 0);
            inode = lookup(parent, path);
            try (RawFd fd1 = inode2fd(inode, O_NOFOLLOW | O_DIRECTORY)) {
                rc = sysVfs.fchown(fd1.fd(), uid, gid);
                checkError(rc == 0);
            }
            return inode;
        }
    }

    @Override
    public boolean move(Inode src, String oldName, Inode dest, String newName) throws IOException {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public Inode parentOf(Inode inode) throws IOException {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public int read(Inode inode, byte[] data, long offset, int count) throws IOException {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public String readlink(Inode inode) throws IOException {
        try (RawFd fd = inode2fd(inode, O_PATH | O_NOFOLLOW)) {
            Stat stat = statByFd(fd);
            byte[] buf = new byte[(int) stat.getSize()];
            int rc = sysVfs.readlinkat(fd.fd(), "", buf, buf.length);
            checkError(rc >= 0);
            return new String(buf, UTF_8);
        }
    }

    @Override
    public void remove(Inode parent, String path) throws IOException {
        try (RawFd fd = inode2fd(parent, O_PATH | O_DIRECTORY)) {
            Inode inode = lookup(parent, path);
            Stat stat = getattr(inode);
            int flags = stat.type() == Stat.Type.DIRECTORY ? AT_REMOVEDIR : 0;
            int rc = sysVfs.unlinkat(fd.fd(), path, flags);
            checkError(rc == 0);
        }
    }

    @Override
    public Inode symlink(Inode parent, String path, String link, Subject subject, int mode) throws IOException {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public WriteResult write(Inode inode, byte[] data, long offset, int count, StabilityLevel stabilityLevel) throws IOException {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public void commit(Inode inode, long offset, int count) throws IOException {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public Stat getattr(Inode inode) throws IOException {
        try (RawFd fd = inode2fd(inode, O_PATH)) {
            return statByFd(fd);
        }
    }

    @Override
    public void setattr(Inode inode, Stat stat) throws IOException {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public nfsace4[] getAcl(Inode inode) throws IOException {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public void setAcl(Inode inode, nfsace4[] acl) throws IOException {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public boolean hasIOLayout(Inode inode) throws IOException {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public AclCheckable getAclCheckable() {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public NfsIdMapping getIdMapper() {
        return idMapper;
    }

    /**
     * Lookup file handle by path
     *
     * @param fd parent directory open file descriptor
     * @param path path within a directory
     * @param flags ...
     * @return file handle
     * @throws IOException
     */
    private KernelFileHandle path2fh(int fd, String path, int flags) throws IOException {

        KernelFileHandle fh = new KernelFileHandle(runtime);

        int[] mntId = new int[1];
        int rc = sysVfs.name_to_handle_at(fd, path, fh, mntId, flags);
        checkError(rc == 0);
        LOG.debug("path = [{}], handle = {}", path, fh);
        return fh;
    }

    private void checkError(boolean condition) throws IOException {

        if (condition) {
            return;
        }

        int errno = runtime.getLastError();
        Errno e = Errno.valueOf(errno);
        String msg = sysVfs.strerror(errno) + " " + e.name() + "(" + errno + ")";
        LOG.info("Last error: {}", msg);

        switch (e) {
            case ENOENT:
                throw new NoEntException(msg);
            case ENOTDIR:
                throw new NotDirException(msg);
            case EISDIR:
                throw new IsDirException(msg);
            case EIO:
                throw new NfsIoException(msg);
            case ENOTEMPTY:
                throw new NotEmptyException(msg);
            default:
                IOException t = new ServerFaultException(msg);
                LOG.error("unhandled exception ", t);
                throw t;
        }
    }

    private RawFd inode2fd(Inode inode, int flags) throws IOException {
        KernelFileHandle fh = toKernelFh(inode);
        int fd = sysVfs.open_by_handle_at(rootFd, fh, flags);
        checkError(fd >= 0);
        return new RawFd(fd);
    }

    private KernelFileHandle toKernelFh(Inode inode) {
        byte[] data = inode.getFileId();
        KernelFileHandle fh = new KernelFileHandle(runtime);
        fh.handleType.set(rootFh.handleType.intValue());
        fh.handleBytes.set(data.length);
        for (int i = 0; i < data.length; i++) {
            fh.handleData[i].set(data[i]);
        }
        return fh;
    }

    private Inode toInode(KernelFileHandle fh) {
        byte[] data = new byte[fh.handleBytes.intValue()];
        for (int i = 0; i < data.length; i++) {
            data[i] = fh.handleData[i].get();
        }
        return Inode.forFile(data);
    }

    private Stat statByFd(RawFd fd) throws IOException {
        FileStat stat = new FileStat(runtime);
        int rc = sysVfs.__fxstat64(0, fd.fd(), stat);
        checkError(rc == 0);
        return toVfsStat(stat);
    }

    private Stat toVfsStat(FileStat fileStat) {
        Stat vfsStat = new Stat();

        vfsStat.setATime(fileStat.st_atime.get() * 1000);
        vfsStat.setCTime(fileStat.st_ctime.get() * 1000);
        vfsStat.setMTime(fileStat.st_mtime.get() * 1000);

        vfsStat.setGid(fileStat.st_gid.get());
        vfsStat.setUid(fileStat.st_uid.get());
        vfsStat.setDev(fileStat.st_dev.intValue());
        vfsStat.setIno(fileStat.st_ino.intValue());
        vfsStat.setMode(fileStat.st_mode.get());
        vfsStat.setNlink(fileStat.st_nlink.intValue());
        vfsStat.setRdev(fileStat.st_rdev.intValue());
        vfsStat.setSize(fileStat.st_size.get());
        vfsStat.setFileid(fileStat.st_ino.get());
        vfsStat.setGeneration(fileStat.st_ctime.get() ^ fileStat.st_mtime.get());

        return vfsStat;
    }

    public interface SysVfs {

        String strerror(int e);

        int open(CharSequence path, int flags, int mode);

        int name_to_handle_at(int fd, CharSequence name, @Out @In KernelFileHandle fh, @Out int[] mntId, int flag);

        int open_by_handle_at(int mount_fd, @In KernelFileHandle fh, int flags);

        int __fxstat64(int version, int fd, @Transient @Out FileStat fileStat);

        Address fdopendir(int fd);

        int closedir(@In Address dirp);

        Dirent readdir(@In @Out Address dirp);

        int readlinkat(int fd, CharSequence path, @Out byte[] buf, int len);

        int unlinkat(int fd, CharSequence path, int flags);

        int close(int fd);

        int mkdirat(int fd, CharSequence path, int mode);

        int fchown(int fd, int uid, int gid);
    }

    private class RawFd implements Closeable {

        private final int fd;

        RawFd(int fd) {
            this.fd = fd;
        }

        int fd() {
            return fd;
        }

        @Override
        public void close() throws IOException {
            int rc = sysVfs.close(fd);
            checkError(rc == 0);
        }

    }
}
