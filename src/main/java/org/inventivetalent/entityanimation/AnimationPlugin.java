package org.inventivetalent.entityanimation;

import org.bukkit.plugin.java.JavaPlugin;
import org.inventivetalent.apihelper.APIManager;

public class AnimationPlugin extends JavaPlugin {

	AnimationAPI apiInstance = new AnimationAPI();

	@Override
	public void onLoad() {
		APIManager.registerAPI(apiInstance, this);

	}

	@Override
	public void onEnable() {
		APIManager.initAPI(AnimationAPI.class);
	}
}
