package de.bedrockvoice;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;

/** Anbindung an die Simple-Voice-Chat-API. */
public final class VoiceIntegration implements VoicechatPlugin {

    private final BridgeManager bridges;

    VoiceIntegration(BridgeManager bridges) {
        this.bridges = bridges;
    }

    @Override
    public String getPluginId() {
        return "bedrockvoicebridge";
    }

    @Override
    public void initialize(VoicechatApi api) { }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, e -> bridges.api = e.getVoicechat());
        registration.registerEvent(VoicechatServerStoppedEvent.class, e -> bridges.api = null);
        registration.registerEvent(MicrophonePacketEvent.class, bridges::onMicrophone);
    }
}
