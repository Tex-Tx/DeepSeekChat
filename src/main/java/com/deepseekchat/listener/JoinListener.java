package com.deepseekchat.listener;

import com.deepseekchat.DeepSeekChat;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

/**
 * 玩家登录时登记 UUID, 并预加载玩家数据行
 */
public class JoinListener implements Listener {

    private final DeepSeekChat plugin;

    public JoinListener(DeepSeekChat plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPostLogin(PostLoginEvent event) {
        ProxiedPlayer player = event.getPlayer();
        plugin.getProxy().getScheduler().runAsync(plugin, () ->
                plugin.getTokenManager().loadPlayer(player.getName(), player.getUniqueId().toString()));
    }
}
