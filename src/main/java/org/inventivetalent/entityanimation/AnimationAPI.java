package org.inventivetalent.entityanimation;

import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.inventivetalent.apihelper.API;
import org.inventivetalent.apihelper.APIManager;
import org.inventivetalent.packetlistener.PacketListenerAPI;
import org.inventivetalent.packetlistener.handler.PacketHandler;
import org.inventivetalent.packetlistener.handler.PacketOptions;
import org.inventivetalent.packetlistener.handler.ReceivedPacket;
import org.inventivetalent.packetlistener.handler.SentPacket;
import org.inventivetalent.reflection.minecraft.Minecraft;
import org.inventivetalent.reflection.resolver.ConstructorResolver;
import org.inventivetalent.reflection.resolver.FieldResolver;
import org.inventivetalent.reflection.resolver.MethodResolver;
import org.inventivetalent.reflection.resolver.ResolverQuery;
import org.inventivetalent.reflection.resolver.minecraft.NMSClassResolver;
import org.inventivetalent.reflection.resolver.minecraft.OBCClassResolver;

import java.lang.reflect.InvocationTargetException;
import java.util.*;

public class AnimationAPI implements API, Listener {

	static           NMSClassResolver nmsClassResolver = new NMSClassResolver();
	protected static OBCClassResolver obcClassResolver = new OBCClassResolver();

	static Class<?> Entity                      = nmsClassResolver.resolveSilent("Entity");
	static Class<?> PacketPlayOutAnimation      = nmsClassResolver.resolveSilent("PacketPlayOutAnimation");
	static Class<?> PacketPlayOutEntityMetadata = nmsClassResolver.resolveSilent("PacketPlayOutEntityMetadata");
	static Class<?> DataWatcher                 = nmsClassResolver.resolveSilent("DataWatcher");
	static Class<?> DataWatcherItem             = nmsClassResolver.resolveSilent("DataWatcher$Item");

	static FieldResolver EntityFieldResolver                 = new FieldResolver(Entity);
	static FieldResolver PacketPlayOutAnimationFieldResolver = new FieldResolver(PacketPlayOutAnimation);
	static FieldResolver CraftWorldFieldResolver;
	static FieldResolver WorldFieldResolver;
	static FieldResolver DataWatcherFieldResolver                 = new FieldResolver(DataWatcher);
	static FieldResolver DataWatcherItemFieldResolver             = new FieldResolver(DataWatcherItem);
	static FieldResolver EntityPlayerFieldResolver                = new FieldResolver(nmsClassResolver.resolveSilent("EntityPlayer"));
	static FieldResolver PacketPlayOutEntityMetadataFieldResolver = new FieldResolver(PacketPlayOutEntityMetadata);

	static MethodResolver EntityMethodResolver = new MethodResolver(Entity);
	static MethodResolver IntHashMapMethodResolver;
	static MethodResolver DataWatcherItemMethodResolver  = new MethodResolver(DataWatcherItem);
	static MethodResolver PlayerConnectionMethodResolver = new MethodResolver(nmsClassResolver.resolveSilent("PlayerConnection"));

	static ConstructorResolver DataWatcherItemConstructorResolver = new ConstructorResolver(DataWatcherItem);

	//Map UUID -> UUID-active states
	static Map<UUID, Map<UUID, Map<State, Boolean>>> stateMap = new HashMap<>();

	@Override
	public void load() {
		APIManager.require(PacketListenerAPI.class, null);
	}

	@Override
	public void init(Plugin plugin) {
		APIManager.initAPI(PacketListenerAPI.class);

		APIManager.registerEvents(this, this);

		PacketHandler.addHandler(new PacketHandler(plugin) {
			@Override
			@PacketOptions(forcePlayer = true)
			public void onSend(SentPacket sentPacket) {
				if ("PacketPlayOutEntityMetadata".equals(sentPacket.getPacketName())) {
					int a = (int) sentPacket.getPacketValue("a");
					if (a < 0) {//Our packet
						//Reset the ID and let it through
						sentPacket.setPacketValue("a", -a);
						return;
					}

					List b = (List) sentPacket.getPacketValue("b");
					if (b == null || b.isEmpty()) {
						return;//Nothing to modify
					}

					Entity entity = getEntityById(sentPacket.getPlayer().getWorld(), a);
					if (entity != null) {
						//Get the states
						Map<State, Boolean> states = AnimationAPI.getStates(entity, sentPacket.getPlayer());

						if (!states.isEmpty()) {
							try {
								//Update the DataWatcher Items
								Object prevItem = b.get(0);
								Object prevObj = DataWatcherItemMethodResolver.resolve("b").invoke(prevItem);
								if (prevObj instanceof Byte) {
									byte bte = (byte) prevObj;
									for (Map.Entry<State, Boolean> entry : states.entrySet()) {
										int index = entry.getKey().value;
										bte = (byte) (entry.getValue() ? (bte | 1 << index) : (bte & ~(1 << index)));
									}
									DataWatcherItemFieldResolver.resolve("b").set(prevItem, bte);
								}
							} catch (Exception e) {
								throw new RuntimeException(e);
							}
						}
					}
				}
			}

			@Override
			public void onReceive(ReceivedPacket receivedPacket) {
			}
		});
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		resetAllStates(event.getPlayer());
	}

	@Override
	public void disable(Plugin plugin) {
	}

	/**
	 * Modify an entity's state globally
	 *
	 * @param entity {@link Entity}
	 * @param state  {@link State}
	 * @param flag   whether the state is active
	 */
	public static void setGlobalState(Entity entity, State state, boolean flag) {
		try {
			EntityMethodResolver.resolve("setFlag").invoke(Minecraft.getHandle(entity), state.value, flag);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Check if an entity's global state is active
	 *
	 * @param entity {@link Entity}
	 * @param state  {@link State}
	 * @return whether the state is active
	 */
	public static boolean hasGlobalState(Entity entity, State state) {
		try {
			return (boolean) EntityMethodResolver.resolve("getFlag").invoke(Minecraft.getHandle(entity), state.value);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Modify an entity's state for a specific receiver
	 *
	 * @param entity   {@link Entity}
	 * @param receiver {@link Player} receiver
	 * @param state    {@link State}
	 * @param flag     whether the state is active
	 */
	public static void setState(Entity entity, Player receiver, State state, boolean flag) {
		Map<UUID, Map<State, Boolean>> receiverMap = stateMap.get(entity.getUniqueId());
		if (receiverMap == null) { receiverMap = new HashMap<>(); }
		Map<State, Boolean> states = receiverMap.get(receiver.getUniqueId());
		if (states == null) { states = new HashMap<>(); }

		states.put(state, flag);
		receiverMap.put(receiver.getUniqueId(), states);
		stateMap.put(entity.getUniqueId(), receiverMap);

		try {
			//Packet
			List list = new ArrayList();

			//Existing values
			Object dataWatcher = EntityMethodResolver.resolve("getDataWatcher").invoke(Minecraft.getHandle(entity));
			Map<Integer, Object> dataWatcherItems = (Map<Integer, Object>) DataWatcherFieldResolver.resolve("c").get(dataWatcher);

			byte bte = (byte) (dataWatcherItems.isEmpty() ? 0 : DataWatcherItemMethodResolver.resolve("b").invoke(dataWatcherItems.get(0)));
			for (Map.Entry<State, Boolean> entry : states.entrySet()) {
				int index = entry.getKey().value;
				bte = (byte) (entry.getValue() ? (bte | 1 << index) : (bte & ~(1 << index)));
			}
			Object dataWatcherItem = DataWatcherItemConstructorResolver.resolveFirstConstructor().newInstance(org.inventivetalent.reflection.minecraft.DataWatcher.V1_9.ValueType.ENTITY_FLAG.getType(), bte);

			//The glowing item
			list.add(dataWatcherItem);

			Object packetMetadata = PacketPlayOutEntityMetadata.newInstance();
			PacketPlayOutEntityMetadataFieldResolver.resolve("a").set(packetMetadata, -entity.getEntityId());//Use the negative ID so we can identify our own packet
			PacketPlayOutEntityMetadataFieldResolver.resolve("b").set(packetMetadata, list);

			sendPacket(packetMetadata, receiver);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Reset an entity's state for a specific receiver (allows vanilla states to be seen again)
	 *
	 * @param entity   {@link Entity}
	 * @param receiver {@link Player} receiver
	 * @param state    {@link State}
	 */
	public static void resetState(Entity entity, Player receiver, State state) {
		if (!stateMap.containsKey(entity.getUniqueId())) { return; }
		Map<UUID, Map<State, Boolean>> receiverMap = stateMap.get(entity.getUniqueId());
		if (!receiverMap.containsKey(receiver.getUniqueId())) { return; }
		Map<State, Boolean> states = receiverMap.get(receiver.getUniqueId());
		states.remove(state);

		//Cleanup
		if (states.isEmpty()) {
			receiverMap.remove(receiver.getUniqueId());
			if (receiverMap.isEmpty()) {
				stateMap.remove(entity.getUniqueId());
			}
		}
	}

	/**
	 * Reset all states of an entity for a specific receiver (allows vanilla states to be seen again)
	 *
	 * @param entity   {@link Entity}
	 * @param receiver {@link Player} receiver
	 */
	public static void resetAllStates(Entity entity, Player receiver) {
		if (!stateMap.containsKey(entity.getUniqueId())) { return; }
		Map<UUID, Map<State, Boolean>> receiverMap = stateMap.get(entity.getUniqueId());
		receiverMap.remove(receiver.getUniqueId());

		//Cleanup
		if (receiverMap.isEmpty()) {
			stateMap.remove(entity.getUniqueId());
		}
	}

	/**
	 * Reset all states of an entiy
	 *
	 * @param entity {@link Entity}
	 */
	public static void resetAllStates(Entity entity) {
		stateMap.remove(entity.getUniqueId());
	}

	/**
	 * Check if an entity's state is active for a specific receiver
	 *
	 * @param entity   {@link Entity}
	 * @param receiver {@link Player} receiver
	 * @param state    {@link State}
	 * @return whether the state is active
	 */
	public static boolean hasState(Entity entity, Player receiver, State state) {
		if (!stateMap.containsKey(entity.getUniqueId())) { return false; }
		Map<UUID, Map<State, Boolean>> receiverMap = stateMap.get(entity.getUniqueId());
		if (!receiverMap.containsKey(receiver.getUniqueId())) { return false; }
		Boolean stateValue = receiverMap.get(receiver.getUniqueId()).get(state);
		return stateValue != null && stateValue;
	}

	/**
	 * Get an entity's states for a specific receiver
	 *
	 * @param entity   {@link Entity}
	 * @param receiver {@link Player} receiver
	 * @return {@link Map} containing the entity's state
	 */
	public static Map<State, Boolean> getStates(Entity entity, Player receiver) {
		if (!stateMap.containsKey(entity.getUniqueId())) { return new HashMap<>(); }
		Map<UUID, Map<State, Boolean>> receiverMap = stateMap.get(entity.getUniqueId());
		if (!receiverMap.containsKey(receiver.getUniqueId())) { return new HashMap<>(); }
		return new HashMap<>(receiverMap.get(receiver.getUniqueId()));
	}

	/**
	 * Play an animation for a specific receiver
	 *
	 * @param entity    {@link Entity}
	 * @param receiver  {@link Player} receiver
	 * @param animation {@link Animation}
	 */
	public static void playAnimation(Entity entity, Player receiver, Animation animation) {
		try {
			Object packet = PacketPlayOutAnimation.newInstance();
			PacketPlayOutAnimationFieldResolver.resolve("a").set(packet, entity.getEntityId());
			PacketPlayOutAnimationFieldResolver.resolve("b").set(packet, animation.ordinal());

			sendPacket(packet, receiver);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	static void sendPacket(Object packet, Player p) throws IllegalArgumentException, IllegalAccessException, InvocationTargetException, ClassNotFoundException, NoSuchFieldException, NoSuchMethodException {
		Object handle = null;
		try {
			handle = Minecraft.getHandle(p);
		} catch (ReflectiveOperationException e) {
			e.printStackTrace();
		}
		final Object connection = EntityPlayerFieldResolver.resolve("playerConnection").get(handle);
		PlayerConnectionMethodResolver.resolve("sendPacket").invoke(connection, packet);
	}

	static Entity getEntityById(World world, int entityId) {
		try {
			if (CraftWorldFieldResolver == null) {
				CraftWorldFieldResolver = new FieldResolver(obcClassResolver.resolve("CraftWorld"));
			}
			if (WorldFieldResolver == null) {
				WorldFieldResolver = new FieldResolver(nmsClassResolver.resolve("World"));
			}
			if (IntHashMapMethodResolver == null) {
				IntHashMapMethodResolver = new MethodResolver(nmsClassResolver.resolve("IntHashMap"));
			}
			if (EntityMethodResolver == null) {
				EntityMethodResolver = new MethodResolver(nmsClassResolver.resolve("Entity"));
			}

			Object entitiesById = WorldFieldResolver.resolve("entitiesById").get(CraftWorldFieldResolver.resolve("world").get(world));
			Object entity = IntHashMapMethodResolver.resolve(new ResolverQuery("get", int.class)).invoke(entitiesById, entityId);
			if (entity == null) { return null; }
			return (Entity) EntityMethodResolver.resolve("getBukkitEntity").invoke(entity);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public enum State {
		ON_FIRE(0),
		CROUCHED(1),
		SPRINTING(3),

		EATING(4),
		DRINKING(4),
		BLOCKING(4),

		INVISIBLE(5),
		GLOWING(6),
		ELYTRA_FLYING(7);

		public int value;

		State(int value) {
			this.value = value;
		}
	}

	public enum Animation {
		SWING_ARM,
		TAKE_DAMGE,
		LEAVE_BED,
		EAT_FOOD,
		CRITICAL_EFFECT,
		MAGIC_CRITIAL_EFFECT;
	}
}
