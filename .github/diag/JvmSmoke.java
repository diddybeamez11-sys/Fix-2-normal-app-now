// Temporary diagnostic helper (NOT part of the product).
// Initialises the same classes as DiagProbe on a plain JVM (no R8, no Android) to separate
// "broken in the library/inputs" from "broken by R8/packaging".
public class JvmSmoke {

    static void report(String what, Throwable t) {
        System.out.println("FAIL " + what + " -> " + t);
        Throwable r = t;
        int d = 0;
        while (r.getCause() != null && r.getCause() != r && d++ < 10) {
            r = r.getCause();
            System.out.println("      caused by: " + r);
        }
        StackTraceElement[] st = r.getStackTrace();
        for (int i = 0; i < Math.min(8, st.length); i++) {
            System.out.println("        at " + st[i]);
        }
    }

    static void init(String name) {
        try {
            Class.forName(name, true, JvmSmoke.class.getClassLoader());
            System.out.println("OK   init " + name);
        } catch (Throwable t) {
            report("init " + name, t);
        }
    }

    static void call(String what, java.util.concurrent.Callable<Object> c) {
        try {
            System.out.println("OK   " + what + " -> " + c.call());
        } catch (Throwable t) {
            report(what, t);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("java " + System.getProperty("java.version"));
        call("Vector3i.from", () -> Class.forName("org.cloudburstmc.math.vector.Vector3i")
                .getMethod("from", int.class, int.class, int.class).invoke(null, 1, 2, 3));
        String[] names = {
                "dev.sora.relay.game.utils.constants.EnumFacing",
                "dev.sora.relay.game.utils.constants.ItemTags",
                "dev.sora.relay.game.utils.MineUtils",
                "dev.sora.relay.game.registry.LegacyBlockMapping",
                "dev.sora.relay.game.registry.ItemMapping$Provider",
                "dev.sora.relay.game.registry.BlockMapping$Provider",
                "dev.sora.relay.game.management.BlobCacheManager",
                "dev.sora.relay.game.entity.EntityLocalPlayer",
                "dev.sora.relay.game.world.Level",
                "dev.sora.relay.game.GameSession",
                "org.cloudburstmc.protocol.bedrock.codec.v844.Bedrock_v844",
                "org.cloudburstmc.protocol.bedrock.codec.compat.BedrockCompat",
                "org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193",
                "dev.sora.relay.session.listener.RelayListenerAutoCodec",
                "dev.sora.relay.MinecraftRelay",
        };
        for (String n : names) {
            init(n);
        }
        call("new GameSession()", () -> Class.forName("dev.sora.relay.game.GameSession").getConstructor().newInstance());
        call("Bedrock_v844.CODEC", () -> {
            Object codec = Class.forName("org.cloudburstmc.protocol.bedrock.codec.v844.Bedrock_v844").getField("CODEC").get(null);
            return codec.getClass().getMethod("getProtocolVersion").invoke(codec) + " / "
                    + codec.getClass().getMethod("getMinecraftVersion").invoke(codec);
        });
        System.exit(0);
    }
}
