package top.leonx.territory.client;

import net.minecraft.client.Minecraft;
import top.leonx.territory.client.screen.TerritoryTableScreen;
import top.leonx.territory.network.FactionInfoS2C;
import top.leonx.territory.network.TerritoryDataS2C;

/**
 * Client-side landing for server payloads. Kept separate from the common network class so it is only
 * classloaded on the client (it is referenced solely inside an enqueueWork lambda on the client thread).
 */
public final class ClientHooks {

    private ClientHooks() {}

    public static void acceptData(TerritoryDataS2C msg) {
        if (Minecraft.getInstance().screen instanceof TerritoryTableScreen screen) {
            screen.acceptData(msg);
        }
    }

    public static void acceptFactionInfo(FactionInfoS2C msg) {
        if (Minecraft.getInstance().screen instanceof TerritoryTableScreen screen) {
            screen.acceptFactionInfo(msg);
        }
    }
}
