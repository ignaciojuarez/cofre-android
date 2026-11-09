package com.ignacio.cofre.glfw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.Struct;

/**
 * Proves the shim is complete for Minecraft 26.2: every org/lwjgl/glfw member the client jar
 * references must resolve, either to the shim or to a pure-Java LWJGL 3.4.1 class (structs,
 * callbacks), and everything those classes reference in turn must resolve the same way. LWJGL
 * classes that call native GLFW (GLFW, GLFWVulkan, GLFWNative*, Callbacks, *$Functions) must
 * come from the shim.
 */
class ShimCompletenessTest {

    private static final String CLIENT_URL =
        "https://piston-data.mojang.com/v1/objects/2dc72797acbc1b63fc16a11c4ac393605f453754/client.jar";
    private static final String CLIENT_SHA1 = "2dc72797acbc1b63fc16a11c4ac393605f453754";
    private static final Pattern NATIVE_BOUND = Pattern.compile("org/lwjgl/glfw/(GLFW|GLFWVulkan|GLFWNative\\w*|Callbacks|.*\\$Functions)");
    private static final Pattern GLFW_TYPE = Pattern.compile("L(org/lwjgl/glfw/[^;]+);");

    record Member(String owner, String name, String desc, boolean field) {
        @Override public String toString() {
            return owner + "." + name + (field ? ":" : "") + desc;
        }
    }

    record ClassInfo(String name, String superName, List<String> interfaces, Set<String> members, Set<String> classRefs, Set<Member> memberRefs) {}

    @Test
    void shimDefinesEverythingMinecraft262Uses() throws Exception {
        Map<String, ClassInfo> shim = readClasses(codeSource(GLFW.class));
        Map<String, ClassInfo> lwjglGlfw = readClasses(codeSource(GLFWVidMode.class));
        Map<String, ClassInfo> lwjglCore = readClasses(codeSource(Struct.class));
        ClassInfo client = merged(readClasses(clientJar()));

        assertTrue(shim.containsKey("org/lwjgl/glfw/GLFW"), "shim classes not found");
        assertTrue(!lwjglGlfw.get("org/lwjgl/glfw/GLFW").members().isEmpty(), "LWJGL glfw jar not found");

        List<String> problems = new ArrayList<>();
        Set<String> usedClasses = new TreeSet<>();
        Set<Member> checked = new LinkedHashSet<>();
        ArrayDeque<ClassInfo> pending = new ArrayDeque<>(List.of(client));
        Set<String> visited = new LinkedHashSet<>();

        while (!pending.isEmpty()) {
            ClassInfo from = pending.poll();
            Set<String> classes = new TreeSet<>(from.classRefs());
            for (Member m : from.memberRefs()) {
                classes.add(m.owner());
                Matcher types = GLFW_TYPE.matcher(m.desc());
                while (types.find()) classes.add(types.group(1));
            }
            for (String name : classes) {
                if (!name.startsWith("org/lwjgl/glfw/")) continue;
                ClassInfo target = shim.get(name);
                if (target == null) {
                    if (NATIVE_BOUND.matcher(name).matches()) {
                        problems.add("must come from the shim (calls native GLFW): " + name + " (used by " + from.name() + ")");
                        continue;
                    }
                    target = lwjglGlfw.get(name);
                }
                if (target == null) {
                    problems.add("missing class " + name + " (used by " + from.name() + ")");
                    continue;
                }
                usedClasses.add(name + (shim.containsKey(name) ? " [shim]" : " [lwjgl]"));
                if (visited.add(name)) pending.add(target);
            }
            for (Member m : from.memberRefs()) {
                if (!m.owner().startsWith("org/lwjgl/glfw/") || !checked.add(m)) continue;
                if (!resolves(m, m.owner(), shim, lwjglGlfw, lwjglCore)) problems.add("missing member " + m + " (used by " + from.name() + ")");
            }
        }

        long clientRefs = client.memberRefs().stream().filter(m -> m.owner().startsWith("org/lwjgl/glfw/")).count();
        System.out.println("GLFW shim coverage: client 26.2 references " + clientRefs + " GLFW members; "
            + checked.size() + " members checked across " + usedClasses.size() + " classes, " + problems.size() + " missing");
        usedClasses.forEach(c -> System.out.println("  " + c));
        assertEquals(List.of(), problems);
    }

    private static boolean resolves(Member m, String owner, Map<String, ClassInfo> shim, Map<String, ClassInfo> glfw, Map<String, ClassInfo> core) {
        if (owner == null) return false;
        if (owner.startsWith("java/")) return javaHas(owner, m);
        ClassInfo c = shim.containsKey(owner) ? shim.get(owner) : glfw.containsKey(owner) ? glfw.get(owner) : core.get(owner);
        if (c == null) return false;
        if (c.members().contains(m.name() + m.desc())) return true;
        if (resolves(m, c.superName(), shim, glfw, core)) return true;
        for (String i : c.interfaces()) if (resolves(m, i, shim, glfw, core)) return true;
        return false;
    }

    private static boolean javaHas(String owner, Member m) {
        try {
            Class<?> type = Class.forName(owner.replace('/', '.'));
            if (m.field()) return Stream.of(type.getFields()).anyMatch(f -> f.getName().equals(m.name()));
            for (Method method : type.getMethods()) if (method.getName().equals(m.name())) return true;
            return false;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static Path codeSource(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static ClassInfo merged(Map<String, ClassInfo> classes) {
        Set<String> refs = new TreeSet<>();
        Set<Member> members = new LinkedHashSet<>();
        classes.values().forEach(c -> {
            refs.addAll(c.classRefs());
            members.addAll(c.memberRefs());
        });
        return new ClassInfo("minecraft-26.2-client", null, List.of(), Set.of(), refs, members);
    }

    /** Downloads the 26.2 client jar once into .work/mc and checks its SHA-1. */
    private static Path clientJar() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("settings.gradle.kts"))) root = root.getParent();
        Path jar = root.resolve(".work/mc/client-26.2.jar");
        if (Files.exists(jar) && sha1(jar).equals(CLIENT_SHA1)) return jar;
        Files.createDirectories(jar.getParent());
        Path part = jar.resolveSibling("client-26.2.jar.part");
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        http.send(HttpRequest.newBuilder(URI.create(CLIENT_URL)).build(), HttpResponse.BodyHandlers.ofFile(part));
        assertEquals(CLIENT_SHA1, sha1(part), "client jar checksum");
        return Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String sha1(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Map<String, ClassInfo> readClasses(Path location) throws IOException {
        Map<String, ClassInfo> classes = new HashMap<>();
        if (Files.isDirectory(location)) {
            try (Stream<Path> files = Files.walk(location)) {
                for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".class"))::iterator) {
                    ClassInfo c = parse(Files.readAllBytes(p));
                    classes.put(c.name(), c);
                }
            }
        } else {
            try (ZipFile zip = new ZipFile(location.toFile())) {
                for (ZipEntry e : java.util.Collections.list(zip.entries())) {
                    if (!e.getName().endsWith(".class") || e.getName().startsWith("META-INF/")) continue;
                    try (InputStream in = zip.getInputStream(e)) {
                        ClassInfo c = parse(in.readAllBytes());
                        classes.put(c.name(), c);
                    }
                }
            }
        }
        return classes;
    }

    /** Reads a class file's constant pool, declared members and super types (JVMS §4). */
    private static ClassInfo parse(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        in.readInt();
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        Object[] pool = new Object[count];
        int[][] refs = new int[count][];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> pool[i] = in.readUTF();
                case 3, 4 -> in.readInt();
                case 5, 6 -> {
                    in.readLong();
                    i++;
                }
                case 7, 8, 16, 19, 20 -> refs[i] = new int[] {tag, in.readUnsignedShort()};
                case 9, 10, 11, 12, 17, 18 -> refs[i] = new int[] {tag, in.readUnsignedShort(), in.readUnsignedShort()};
                case 15 -> {
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                }
                default -> throw new IOException("bad constant tag " + tag);
            }
        }
        Set<String> classRefs = new TreeSet<>();
        Set<Member> memberRefs = new LinkedHashSet<>();
        for (int i = 1; i < count; i++) {
            if (refs[i] == null) continue;
            int tag = refs[i][0];
            if (tag == 7) classRefs.add(arrayElement((String) pool[refs[i][1]]));
            if (tag == 9 || tag == 10 || tag == 11) {
                String owner = arrayElement((String) pool[refs[refs[i][1]][1]]);
                int[] nameAndType = refs[refs[i][2]];
                memberRefs.add(new Member(owner, (String) pool[nameAndType[1]], (String) pool[nameAndType[2]], tag == 9));
            }
        }
        in.readUnsignedShort();
        String name = (String) pool[refs[in.readUnsignedShort()][1]];
        int superIndex = in.readUnsignedShort();
        String superName = superIndex == 0 ? null : (String) pool[refs[superIndex][1]];
        List<String> interfaces = new ArrayList<>();
        for (int i = in.readUnsignedShort(); i > 0; i--) interfaces.add((String) pool[refs[in.readUnsignedShort()][1]]);
        Set<String> members = new LinkedHashSet<>();
        for (int kind = 0; kind < 2; kind++) {
            for (int i = in.readUnsignedShort(); i > 0; i--) {
                in.readUnsignedShort();
                members.add((String) pool[in.readUnsignedShort()] + pool[in.readUnsignedShort()]);
                for (int a = in.readUnsignedShort(); a > 0; a--) {
                    in.readUnsignedShort();
                    in.skipNBytes(in.readInt());
                }
            }
        }
        return new ClassInfo(name, superName, interfaces, members, classRefs, memberRefs);
    }

    private static String arrayElement(String name) {
        Matcher m = Pattern.compile("^\\[+L(.+);$").matcher(name);
        return m.matches() ? m.group(1) : name;
    }
}
