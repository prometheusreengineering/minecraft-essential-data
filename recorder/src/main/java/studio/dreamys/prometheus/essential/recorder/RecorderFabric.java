package studio.dreamys.prometheus.essential.recorder;

import ing.prometheusreengineer.verity.api.Verity;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Registers the recorder's mixin the way the Prometheus patch does on Fabric: Essential's classes come through its
 * loader, after Fabric has read every mod's mixin configs, so the config is added late and Mixin is made to pick it up.
 */
public final class RecorderFabric implements PreLaunchEntrypoint {
    static final String MIXIN_CONFIG = "prometheus.essential.recorder.mixins.json";

    @Override
    public void onPreLaunch() {
        if (!Verity.isActive()) return;
        MixinBootstrap.init();
        Mixins.addConfiguration(MIXIN_CONFIG);
        try {
            // https://github.com/SparkUniverse/EssentialLoader/blob/73f3915c1e1ffc6b42bffad52c709d9874889898/stage2/fabric/src/main/java/gg/essential/loader/stage2/EssentialLoader.java#L178
            if (Mixins.getUnvisitedCount() != 0) {
                MixinEnvironment environment = MixinEnvironment.getDefaultEnvironment();
                Object transformer = environment.getActiveTransformer();
                Field processorField = transformer.getClass().getDeclaredField("processor");
                processorField.setAccessible(true);
                Object processor = processorField.get(transformer);
                Method select = processor.getClass().getDeclaredMethod("select", MixinEnvironment.class);
                select.setAccessible(true);
                select.invoke(processor, environment);
            }
        } catch (ReflectiveOperationException e) {
            Verity.fail("register the recorder's mixins", e);
        }
    }
}
