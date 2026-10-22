import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Проверка связывания без сервера: каждая ссылка из классов плагина на org.bukkit.* / com.vexsoftware.* (поля, методы,
 * конструкторы, классы из constant pool) ищется в указанных API-jar рефлексией (с учётом наследования).
 * Не запускает плагин и не заменяет real-server тест: показывает, что JVM сможет связать байткод с этим API.
 *   java ApiLinkCheck.java <plugin.jar> <api.jar>[:<api2.jar>...] [--guarded Class.member,...] [--load]
 * --load: дополнительно загрузить и инициализировать все классы плагина (верификатор байткода текущей JVM, например Java 8).
 * guarded — ссылки, вызов которых обёрнут в try/catch LinkageError (например Vote.getAdditionalData для classic Votifier).
 * Код выхода: 0 — все неохраняемые ссылки найдены.
 */
public class ApiLinkCheck {
    public static void main(String[] a) throws Exception {
        List<URL> urls = new ArrayList<>();
        for (String p : a[1].split(File.pathSeparator)) urls.add(new File(p).toURI().toURL());
        Set<String> guarded = new HashSet<>();
        boolean load = false;
        for (int i = 2; i < a.length; i++) {
            if (a[i].equals("--guarded")) guarded.addAll(Arrays.asList(a[++i].split(",")));
            else if (a[i].equals("--load")) load = true;
        }
        ClassLoader api = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getSystemClassLoader().getParent());
        TreeSet<String> refs = new TreeSet<>();
        try (JarFile jar = new JarFile(a[0])) {
            for (JarEntry e : Collections.list(jar.entries())) {
                if (!e.getName().endsWith(".class")) continue;
                try (InputStream in = jar.getInputStream(e)) { refs.addAll(scan(new DataInputStream(in))); }
            }
        }
        int missing = 0, ok = 0, guardedMissing = 0;
        for (String r : refs) {
            String[] p = r.split(" ", 4);   // kind owner name desc
            String owner = p[1].replace('/', '.');
            if (!owner.startsWith("org.bukkit.") && !owner.startsWith("com.vexsoftware.")) continue;
            String res = resolve(api, p[0], owner, p.length > 2 ? p[2] : null, p.length > 3 ? p[3] : null);
            String id = owner.substring(owner.lastIndexOf('.') + 1) + (p.length > 2 ? "." + p[2] : "");
            if (res == null) { ok++; continue; }
            if (guarded.contains(id)) { guardedMissing++; System.out.println("GUARDED " + r + "  (" + res + ")"); }
            else { missing++; System.out.println("MISSING " + r + "  (" + res + ")"); }
        }
        System.out.println("resolved=" + ok + " missing=" + missing + " guarded_missing=" + guardedMissing);
        int loadFail = 0, loaded = 0;
        if (load) {
            ClassLoader pl = new URLClassLoader(new URL[]{new File(a[0]).toURI().toURL()}, api);
            try (JarFile jar = new JarFile(a[0])) {
                for (JarEntry e : Collections.list(jar.entries())) {
                    if (!e.getName().endsWith(".class")) continue;
                    String cn = e.getName().substring(0, e.getName().length() - 6).replace('/', '.');
                    try { Class.forName(cn, true, pl); loaded++; }
                    catch (Throwable t) { loadFail++; System.out.println("LOAD_FAIL " + cn + ": " + t); }
                }
            }
            System.out.println("java=" + System.getProperty("java.version") + " loaded=" + loaded + " load_fail=" + loadFail);
        }
        System.exit(missing == 0 && loadFail == 0 ? 0 : 1);
    }

    static String resolve(ClassLoader cl, String kind, String owner, String name, String desc) {
        Class<?> c;
        try { c = Class.forName(owner, false, cl); } catch (Throwable t) { return "нет класса"; }
        if (kind.equals("C")) return null;
        try {
            if (kind.equals("F")) {
                for (Class<?> k = c; k != null; k = k.getSuperclass())
                    for (Field f : k.getDeclaredFields()) if (f.getName().equals(name)) return null;
                for (Class<?> i : allInterfaces(c)) for (Field f : i.getDeclaredFields()) if (f.getName().equals(name)) return null;
                return "нет поля";
            }
            if (name.equals("<init>")) {
                for (Constructor<?> k : c.getDeclaredConstructors()) if (desc(k.getParameterTypes(), void.class).equals(desc)) return null;
                return "нет конструктора";
            }
            List<Class<?>> chain = new ArrayList<>();
            for (Class<?> k = c; k != null; k = k.getSuperclass()) chain.add(k);
            chain.addAll(allInterfaces(c));
            for (Class<?> k : chain)
                for (Method m : k.getDeclaredMethods())
                    if (m.getName().equals(name) && desc(m.getParameterTypes(), m.getReturnType()).equals(desc)) return null;
            return "нет метода с такой сигнатурой";
        } catch (Throwable t) { return t.toString(); }
    }

    static Set<Class<?>> allInterfaces(Class<?> c) {
        Set<Class<?>> out = new LinkedHashSet<>();
        Deque<Class<?>> q = new ArrayDeque<>();
        for (Class<?> k = c; k != null; k = k.getSuperclass()) q.addAll(Arrays.asList(k.getInterfaces()));
        while (!q.isEmpty()) { Class<?> i = q.poll(); if (out.add(i)) q.addAll(Arrays.asList(i.getInterfaces())); }
        return out;
    }

    static String desc(Class<?>[] params, Class<?> ret) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : params) sb.append(d(p));
        return sb.append(')').append(d(ret)).toString();
    }

    static String d(Class<?> c) {
        if (c.isArray()) return c.getName().replace('.', '/');
        if (!c.isPrimitive()) return "L" + c.getName().replace('.', '/') + ";";
        if (c == void.class) return "V"; if (c == int.class) return "I"; if (c == long.class) return "J"; if (c == boolean.class) return "Z";
        if (c == double.class) return "D"; if (c == float.class) return "F"; if (c == byte.class) return "B"; if (c == char.class) return "C";
        return "S";
    }

    /** Минимальный разбор constant pool: Class, Fieldref, Methodref, InterfaceMethodref. */
    static List<String> scan(DataInputStream in) throws IOException {
        in.readInt(); in.readUnsignedShort(); in.readUnsignedShort();
        int n = in.readUnsignedShort();
        Object[] cp = new Object[n];
        int[][] refs = new int[n][];
        int[] cls = new int[n];
        int[][] nat = new int[n][];
        char[] kind = new char[n];
        for (int i = 1; i < n; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1: cp[i] = in.readUTF(); break;
                case 3: case 4: in.readInt(); break;
                case 5: case 6: in.readLong(); i++; break;
                case 7: cls[i] = in.readUnsignedShort(); kind[i] = 'C'; break;
                case 8: case 16: case 19: case 20: in.readUnsignedShort(); break;
                case 9: case 10: case 11: refs[i] = new int[]{in.readUnsignedShort(), in.readUnsignedShort()}; kind[i] = tag == 9 ? 'F' : 'M'; break;
                case 12: nat[i] = new int[]{in.readUnsignedShort(), in.readUnsignedShort()}; break;
                case 15: in.readUnsignedByte(); in.readUnsignedShort(); break;
                case 17: case 18: in.readUnsignedShort(); in.readUnsignedShort(); break;
                default: throw new IOException("tag " + tag);
            }
        }
        List<String> out = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            if (kind[i] == 'C') {
                String name = (String) cp[cls[i]];
                if (!name.startsWith("[")) out.add("C " + name);
            } else if (refs[i] != null) {
                String owner = (String) cp[cls[refs[i][0]]];
                int[] nt = nat[refs[i][1]];
                out.add(kind[i] + " " + owner + " " + cp[nt[0]] + " " + cp[nt[1]]);
            }
        }
        return out;
    }
}
