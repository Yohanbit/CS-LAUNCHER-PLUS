import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.commons.compress.archivers.tar.*;
import org.tukaani.xz.*;
import net.kdt.pojavlaunch.multirt.RuntimeArchiveExtractor;

/** Real tar.xz + host filesystem links through the production extractor, not path-only mocks. */
public class RuntimeArchiveExtractionTest {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static class Item {
        String name, target, data; byte type;
        Item(String name, byte type, String target, String data) { this.name=name;this.type=type;this.target=target;this.data=data; }
    }
    static Item file(String name, String data) { return new Item(name,TarConstants.LF_NORMAL,null,data); }
    static Item sym(String name,String target) { return new Item(name,TarConstants.LF_SYMLINK,target,null); }
    static Item hard(String name,String target) { return new Item(name,TarConstants.LF_LINK,target,null); }
    static byte[] archive(Item... items) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new XZOutputStream(bytes,new LZMA2Options()))) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Item item:items) {
                TarArchiveEntry entry = new TarArchiveEntry(item.name,item.type,true);
                if(item.target!=null) entry.setLinkName(item.target);
                byte[] data = item.data==null?new byte[0]:item.data.getBytes("UTF-8");
                entry.setSize(data.length); tar.putArchiveEntry(entry); if(data.length>0)tar.write(data); tar.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }
    static RuntimeArchiveExtractor.Links links = new RuntimeArchiveExtractor.Links() {
        public void symlink(String target,String path) throws IOException { Files.createSymbolicLink(Paths.get(path),Paths.get(target)); }
        public void hardlink(String target,String path) throws IOException { Files.createLink(Paths.get(path),Paths.get(target)); }
        public String readlink(String path) throws IOException {
            Path p=Paths.get(path);return Files.isSymbolicLink(p)?Files.readSymbolicLink(p).toString():null;
        }
    };
    static void extract(Path dest, Item...items) throws IOException {
        RuntimeArchiveExtractor.extract(new ByteArrayInputStream(archive(items)),dest.toFile(),links,null);
    }
    static void reject(Path dest, Item...items) throws IOException {
        try { extract(dest,items); throw new AssertionError("Unsafe archive accepted"); }
        catch (IOException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        if (Boolean.getBoolean("runtime.requireExact")) {
            check("1.24.0".equals(TarArchiveInputStream.class.getPackage().getImplementationVersion()), "CI must use declared commons-compress 1.24.0");
            check("1.8".equals(XZInputStream.class.getPackage().getImplementationVersion()), "CI must use declared xz 1.8");
        }
        Path base=Files.createTempDirectory("runtime-tar");Path dest=base.resolve("root");Files.createDirectory(dest);
        extract(dest,file("release","JAVA_VERSION=\"17\""),file("lib/core.so","native"),sym("lib/alias.so","core.so"),hard("lib/hard.so","lib/core.so"));
        check(Files.isSymbolicLink(dest.resolve("lib/alias.so")),"symlink not fake file");
        check(Files.readSymbolicLink(dest.resolve("lib/alias.so")).toString().equals("core.so"),"Os target/linkpath order");
        check(Files.isSameFile(dest.resolve("lib/hard.so"),dest.resolve("lib/core.so")),"hardlink is same inode");
        extract(dest,hard("forward.so","later.so"),file("later.so","late"));
        check(Files.isSameFile(dest.resolve("forward.so"),dest.resolve("later.so")),"forward hardlink preserved");
        extract(dest,sym("lib/root-core.so","../later.so"));
        check(new String(Files.readAllBytes(dest.resolve("lib/root-core.so")),"UTF-8").equals("late"),"safe parent-relative symlink preserved");
        extract(dest,file("later.so","LATE"));
        check(new String(Files.readAllBytes(dest.resolve("later.so")),"UTF-8").equals("LATE"),"same-length update not skipped");
        reject(dest,file("../escaped","bad"));check(!Files.exists(base.resolve("escaped")),"traversal created no outside file");
        reject(dest,file(base.resolve("absolute").toString(),"bad"));check(!Files.exists(base.resolve("absolute")),"absolute created no file");
        reject(dest,sym("outside","../outside"),file("outside/file","bad"));
        reject(dest,sym("abs",base.resolve("abs-target").toString()));
        reject(dest,hard("unsafe-hard","../outside"));
        reject(dest,hard("missing","absent"));
        reject(dest,hard("cycle-a","cycle-b"),hard("cycle-b","cycle-a"));
        reject(dest,new Item("device",TarConstants.LF_CHR,null,null));
        Path outside=base.resolve("outside-dir");Files.createDirectory(outside);
        Files.createSymbolicLink(dest.resolve("preexisting"),outside);
        reject(dest,file("preexisting/escaped","bad"));check(!Files.exists(outside.resolve("escaped")),"preexisting escaping symlink rejected");
        Files.createSymbolicLink(dest.resolve("dangling"),base.resolve("not-yet-created"));
        reject(dest,file("dangling/escaped","bad"));check(!Files.exists(base.resolve("not-yet-created")),"dangling outside ancestor rejected");
        extract(dest,new Item("safe-dir/",TarConstants.LF_DIR,null,null),sym("dir-alias","safe-dir"),file("dir-alias/child","safe"));
        check(new String(Files.readAllBytes(dest.resolve("safe-dir/child")),"UTF-8").equals("safe"),"later entry through safe symlink preserved");
        try { RuntimeArchiveExtractor.runtimeDirectory(base.toFile(),"../outside");throw new AssertionError("runtime name escape"); }
        catch(IOException expected){checks++;}
        check(RuntimeArchiveExtractor.runtimeDirectory(base.toFile(),"External-21").equals(base.resolve("External-21").toFile()),"normal name");
        try { RuntimeArchiveExtractor.checkedPath(dest.toFile(),"lib/../../escape");throw new AssertionError("metadata folder escape"); }
        catch(IOException expected){checks++;}
        System.out.println("Runtime archive extraction: "+checks+" checks passed (real host tar.xz/symlinks/hardlinks; Android Os adapter not executed)");
        System.out.println("commons-compress="+TarArchiveInputStream.class.getPackage().getImplementationVersion()
                +"; xz="+XZInputStream.class.getPackage().getImplementationVersion());
    }
}
