package studio.dreamys.prometheus.essential.recorder;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Everything the recorder touches in Essential, checked against the bytecode of the Essential build it's compiled
 * against (the latest release on CI), so an Essential update that renames one fails the build instead of the hourly
 * run. Reads class files with ASM: loading them would need the Kotlin and elementa libraries nested in Essential's jar.
 */
class EssentialTargetsTest {
    /** "name descriptor" of every method and field of the class. */
    private static Set<String> members(String className) throws IOException {
        Set<String> members = new HashSet<>();
        try (InputStream in = EssentialTargetsTest.class.getClassLoader().getResourceAsStream(className.replace('.', '/') + ".class")) {
            assertNotNull(in, className + " is not in this Essential build");
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    members.add(name + " " + desc);
                    return null;
                }

                @Override
                public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                    members.add(name + " " + desc);
                    return null;
                }
            }, ClassReader.SKIP_CODE);
        }
        return members;
    }

    private static void assertHas(String className, String... wanted) throws IOException {
        Set<String> members = members(className);
        for (String w : wanted) assertTrue(members.contains(w), className + " has no " + w);
    }

    @Test
    void cosmeticsManager() throws IOException {
        assertHas("gg.essential.network.connectionmanager.cosmetics.CosmeticsManager",
                "tick (Lgg/essential/event/client/ClientTickEvent;)V",
                "connectionManager Lgg/essential/network/connectionmanager/ConnectionManager;",
                "infraCosmeticsData Lgg/essential/network/connectionmanager/cosmetics/InfraCosmeticsData;",
                "getCosmeticsLoaded ()Lgg/essential/gui/elementa/state/v2/State;",
                "getCosmetics ()Lgg/essential/gui/elementa/state/v2/State;");
        assertHas("gg.essential.network.connectionmanager.cosmetics.InfraCosmeticsData",
                "requestCosmeticsIfMissing (Ljava/util/Collection;)V");
        assertHas("gg.essential.network.connectionmanager.ConnectionManager", "isOpen ()Z");
        assertHas("gg.essential.network.cosmetics.Cosmetic", "getId ()Ljava/lang/String;");
    }

    @Test
    void termsAndPaths() throws IOException {
        assertHas("gg.essential.data.OnboardingData", "hasAcceptedTos ()Z", "setAcceptedTos ()V");
        assertHas("gg.essential.util.MagicPathsKt", "getGlobalEssentialDirectory ()Ljava/nio/file/Path;");
    }
}
