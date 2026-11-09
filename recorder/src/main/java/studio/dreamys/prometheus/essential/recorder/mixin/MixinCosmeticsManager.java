package studio.dreamys.prometheus.essential.recorder.mixin;

import gg.essential.event.client.ClientTickEvent;
import gg.essential.network.connectionmanager.ConnectionManager;
import gg.essential.network.connectionmanager.cosmetics.CosmeticsManager;
import gg.essential.network.connectionmanager.cosmetics.InfraCosmeticsData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studio.dreamys.prometheus.essential.recorder.Recorder;

/** Drives {@link Recorder} from Essential's cosmetics tick, which runs on the main thread, connected or not. */
@Mixin(value = CosmeticsManager.class, remap = false)
public abstract class MixinCosmeticsManager {
    @Shadow
    @Final
    private ConnectionManager connectionManager;

    @Shadow
    @Final
    private InfraCosmeticsData infraCosmeticsData;

    @Inject(method = "tick", at = @At("HEAD"))
    private void recorder$tick(ClientTickEvent event, CallbackInfo ci) {
        Recorder.tick((CosmeticsManager) (Object) this, connectionManager, infraCosmeticsData);
    }
}
