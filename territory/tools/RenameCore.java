import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class RenameCore {

    private static final String OLD_PACKAGE = "com/jpreiss/easy_factions/";
    private static final String NEW_PACKAGE = "dev/xmat5/holdfast/";
    private static final String OLD_ASSETS = "assets/easy_factions/";
    private static final String NEW_ASSETS = "assets/holdfast_factions/";
    private static final long FIXED_TIME = 315532800000L;

    private static final String[][] TEXT_RULES = {
            {"com.jpreiss.easy_factions", "dev.xmat5.holdfast"},
            {"com/jpreiss/easy_factions", "dev/xmat5/holdfast"},
            {"com$jpreiss$easy_factions", "dev$xmat5$holdfast"},
            {"Easy Factions", "Holdfast Factions"},
            {"EasyFactions", "HoldfastFactions"},
            {"easy_factions", "holdfast_factions"},
            {"easy-factions", "holdfast-factions"}
    };

    private static final Map<String, String> EXACT_RULES = Map.of(
            "faction_claims", "holdfast_claims",
            "easy_factions_objectives", "easy_factions_objectives"
    );

    private static final List<String> DROPPED_PREFIXES = List.of("top/", "com/newtl/", "assets/territory/", "data/territory/");
    private static final Set<String> DROPPED_EXACT = Set.of("META-INF/neoforge.mods.toml", "META-INF/MANIFEST.MF");
    private static final List<String> TEXT_SUFFIXES = List.of(".json", ".toml", ".mcmeta", ".txt", ".cfg", ".properties", ".lang", ".md");

    private RenameCore() {}

    private static String text(String s) {
        for (String[] rule : TEXT_RULES) {
            s = s.replace(rule[0], rule[1]);
        }
        return s;
    }

    private static final class Renamer extends Remapper {
        @Override
        public String map(String internalName) {
            if (internalName.startsWith(OLD_PACKAGE)) {
                internalName = NEW_PACKAGE + internalName.substring(OLD_PACKAGE.length());
            }
            return internalName.replace("EasyFactions", "HoldfastFactions");
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            return text(name);
        }

        @Override
        public Object mapValue(Object value) {
            if (value instanceof String s) {
                String exact = EXACT_RULES.get(s);
                return exact != null ? exact : text(s);
            }
            return super.mapValue(value);
        }
    }

    private static boolean dropped(String name) {
        if (name.endsWith("/") || DROPPED_EXACT.contains(name)) {
            return true;
        }
        for (String prefix : DROPPED_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA"));
    }

    private static boolean isText(String name) {
        for (String suffix : TEXT_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        in.transferTo(out);
        return out.toByteArray();
    }

    private static byte[] renameClass(byte[] bytes, Renamer renamer, String[] newNameOut) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(0);
        ClassVisitor source = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visitSource(String file, String debug) {
                super.visitSource(file == null ? null : text(file), debug);
            }
        };
        reader.accept(new ClassRemapper(source, renamer), 0);
        newNameOut[0] = renamer.map(reader.getClassName()) + ".class";
        return writer.toByteArray();
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: RenameCore <input.jar> <output.jar>");
        }
        Path input = Paths.get(args[0]);
        Path output = Paths.get(args[1]);
        Files.createDirectories(output.toAbsolutePath().getParent());

        Renamer renamer = new Renamer();
        Map<String, byte[]> result = new TreeMap<>();
        Set<String> duplicates = new TreeSet<>();
        int classes = 0;
        int resources = 0;

        try (ZipFile zip = new ZipFile(input.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (dropped(name)) {
                    continue;
                }
                byte[] data;
                try (InputStream in = zip.getInputStream(entry)) {
                    data = readAll(in);
                }
                String target;
                if (name.endsWith(".class")) {
                    String[] newName = new String[1];
                    data = renameClass(data, renamer, newName);
                    target = newName[0];
                    classes++;
                } else {
                    target = name.startsWith(OLD_ASSETS) ? NEW_ASSETS + name.substring(OLD_ASSETS.length()) : name;
                    if (isText(name)) {
                        data = text(new String(data, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                    }
                    resources++;
                }
                if (result.put(target, data) != null) {
                    duplicates.add(target);
                }
            }
        }
        if (!duplicates.isEmpty()) {
            throw new IllegalStateException("renaming produced duplicate entries: " + duplicates);
        }

        try (OutputStream file = Files.newOutputStream(output); ZipOutputStream zip = new ZipOutputStream(file)) {
            for (Map.Entry<String, byte[]> e : result.entrySet()) {
                ZipEntry out = new ZipEntry(e.getKey());
                out.setTime(FIXED_TIME);
                zip.putNextEntry(out);
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        System.out.println("renamed " + classes + " classes and " + resources + " resources into " + output);
    }
}
