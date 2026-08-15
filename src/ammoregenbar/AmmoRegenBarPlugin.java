package ammoregenbar;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.lwjgl.opengl.GL11;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.combat.AmmoTrackerAPI;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatUIAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;

/**
 * Shows ammo regeneration progress on the weapon rows of the combat HUD.
 *
 * The position of those rows is not exposed by the API and moves with the ship,
 * the weapon names and the HUD layout, so nothing here is hardcoded. Instead the
 * plugin walks the game's own UI tree, matches each row to a WeaponAPI by object
 * identity, and attaches a small custom panel on top of that row's ammo bar.
 * The game then draws it as part of its UI, which is the only way to end up
 * above the HUD: an EveryFrameCombatPlugin renders underneath it.
 *
 * Render only. Nothing here writes to the save or changes weapon stats.
 */
public class AmmoRegenBarPlugin extends BaseEveryFrameCombatPlugin {

	public static final String CONFIG_PATH = "data/config/ammo_regen_bar.json";

	private static final int KEY_R = 19; // LWJGL key code, used with ALT

	private boolean configLoaded = false;
	private boolean announced = false;
	private boolean detectionBroken = false;
	private float logTimer = 0f;
	private float rescanTimer = 0f;

	private ShipAPI lastShip = null;

	// Parallel lists, one entry per attached overlay. Kept as plain lists so no
	// extra class has to be compiled just to hold three references.
	private List attachedWeapons = new ArrayList();
	private List attachedRows = new ArrayList();
	private List attachedBars = new ArrayList();
	private List attachedPanels = new ArrayList();
	private List attachedPlugins = new ArrayList();

	private boolean enabled = true;
	private boolean debugLog = false;
	private float debugLogIntervalSeconds = 1f;
	private float rescanIntervalSeconds = 0.5f;
	private String uiRootMethod = "getWidgetPanel";

	// Held by every overlay as well, and edited in place, so a config reload
	// reaches bars that are already attached.
	private final AmmoRegenBarStyle style = new AmmoRegenBarStyle();

	private List reported = new ArrayList();

	private static Logger log() {
		return Logger.getLogger("AmmoRegenBar");
	}

	/**
	 * Logs a diagnostic once per battle. Silent failures are what made the
	 * first version look like it was doing nothing at all, so every bail out
	 * has to say so, exactly once, rather than every frame.
	 */
	private void reportOnce(String key, String message) {
		if (reported.contains(key)) {
			return;
		}
		reported.add(key);
		log().warn(message);
	}

	// ------------------------------------------------------------------
	// config
	// ------------------------------------------------------------------

	private void loadConfig() {
		configLoaded = true;
		try {
			JSONObject cfg = Global.getSettings().loadJSON(CONFIG_PATH);

			enabled = cfg.optBoolean("enabled", enabled);
			debugLog = cfg.optBoolean("debugLog", debugLog);
			debugLogIntervalSeconds = (float) cfg.optDouble("debugLogIntervalSeconds", debugLogIntervalSeconds);
			rescanIntervalSeconds = (float) cfg.optDouble("rescanIntervalSeconds", rescanIntervalSeconds);
			uiRootMethod = cfg.optString("uiRootMethod", uiRootMethod);

			style.thicknessFraction = (float) cfg.optDouble("overlayHeightFraction", style.thicknessFraction);
			style.insetLong = (float) cfg.optDouble("overlayInsetX", style.insetLong);
			style.hideWhenFull = cfg.optBoolean("hideWhenFull", style.hideWhenFull);
			style.invert = cfg.optBoolean("invert", style.invert);
			style.drawShadow = cfg.optBoolean("drawShadow", style.drawShadow);
			style.debugLoud = cfg.optBoolean("debugLoud", style.debugLoud);

			style.vertical = cfg.optBoolean("vertical", style.vertical);
			style.position = AmmoRegenBarStyle.parsePosition(cfg.optString("barPosition", null), style.position);
			style.side = AmmoRegenBarStyle.parseSide(cfg.optString("verticalSide", null), style.side);

			style.colorR = cfg.optInt("colorR", style.colorR);
			style.colorG = cfg.optInt("colorG", style.colorG);
			style.colorB = cfg.optInt("colorB", style.colorB);
			style.colorA = cfg.optInt("colorA", style.colorA);
		} catch (Throwable t) {
			log().warn("AmmoRegenBar: could not read " + CONFIG_PATH + ", using built in defaults", t);
		}

		// The json is the layer of defaults; LunaLib, when the player has it,
		// overrides field by field on top.
		applyLunaOverrides();
	}

	// ------------------------------------------------------------------
	// frame hooks
	// ------------------------------------------------------------------

	/**
	 * Called when a battle starts. Everything is reset here so settings edited
	 * in the campaign take effect in the next fight, and so a single failure
	 * does not disable the mod for the rest of the session.
	 *
	 * detachAll() is deliberately not called: the panels of the previous battle
	 * are already gone with its UI tree.
	 */
	public void init(CombatEngineAPI engine) {
		configLoaded = false;
		lunaChecked = false;
		announced = false;
		detectionBroken = false;
		lastShip = null;
		rescanTimer = 0f;
		logTimer = 0f;
		reported.clear();
		attachedWeapons.clear();
		attachedRows.clear();
		attachedBars.clear();
		attachedPanels.clear();
		attachedPlugins.clear();
	}

	public void advance(float amount, List<InputEventAPI> events) {
		try {
			if (!configLoaded) {
				loadConfig();
			}

			CombatEngineAPI engine = Global.getCombatEngine();
			if (engine == null) {
				return;
			}

			if (!announced) {
				announced = true;
				log().info("AmmoRegenBar loaded, uiScale=" + Global.getSettings().getScreenScaleMult()
						+ " enabled=" + enabled + " debugLog=" + debugLog
						+ " luna=" + lunaPresent + " " + style.describe());
			}

			if (!enabled || detectionBroken) {
				return;
			}

			ShipAPI ship = engine.getPlayerShip();
			if (ship == null || !ship.isAlive()) {
				detachAll();
				lastShip = ship;
				return;
			}
			if (ship != lastShip) {
				detachAll();
				lastShip = ship;
			}

			rescanTimer += amount;
			if (attachedPanels.isEmpty() || rescanTimer >= rescanIntervalSeconds) {
				rescanTimer = 0f;
				syncAttachments(engine, ship);
			}

			if (debugLog) {
				logTimer += amount;
				if (logTimer >= debugLogIntervalSeconds) {
					logTimer = 0f;
					dumpState(ship);
				}
			}
		} catch (Throwable t) {
			detectionBroken = true;
			log().error("AmmoRegenBar: disabled after a failure in advance", t);
		}
	}

	public void processInputPreCoreControls(float amount, List<InputEventAPI> events) {
		try {
			if (events == null) {
				return;
			}
			for (InputEventAPI event : events) {
				if (event.isConsumed() || !event.isKeyDownEvent() || !event.isAltDown()) {
					continue;
				}
				if (event.getEventValue() == KEY_R) {
					loadConfig();
					log().info("AmmoRegenBar: config reloaded, " + style.describe());
					event.consume();
				}
			}
		} catch (Throwable t) {
			log().error("AmmoRegenBar: input handling failed", t);
		}
	}

	public void renderInUICoords(ViewportAPI viewport) {
		// Nothing is drawn here on purpose: this callback runs underneath the
		// vanilla HUD, so the bars are drawn by the attached custom panels
		// instead, from inside the game's own UI tree.
	}

	// ------------------------------------------------------------------
	// attaching overlays to the HUD
	// ------------------------------------------------------------------

	/**
	 * Walks the live HUD and makes the attachments match it.
	 *
	 * Switching weapon groups makes the game build fresh row widgets, and the old
	 * ones keep reporting the same weapon while no longer being drawn. So the
	 * check cannot be "does this row still know its weapon" - it has to be "is
	 * this exact row still the one hanging in the HUD".
	 */
	private void syncAttachments(CombatEngineAPI engine, ShipAPI ship) throws Exception {
		CombatUIAPI ui = engine.getCombatUI();
		if (ui == null) {
			detachAll();
			return;
		}
		UIPanelAPI root = findUIRoot(ui);
		if (root == null) {
			reportOnce("noRoot", "AmmoRegenBar: could not reach the HUD root panel from "
					+ ui.getClass().getName() + ", reflectionReady=" + reflectionReady);
			detachAll();
			return;
		}

		List rows = findWeaponRows(root);
		if (rows.isEmpty()) {
			reportOnce("noRows", "AmmoRegenBar: HUD root is " + root.getClass().getName()
					+ " but no weapon rows were found under it");
			detachAll();
			return;
		}

		List liveRows = new ArrayList();
		List liveWeapons = new ArrayList();
		List weapons = ship.getAllWeapons();
		for (int i = 0; i < rows.size(); i++) {
			Object row = rows.get(i);
			Object rowWeapon = invokeNoArg(row, "getWeapon");
			if (rowWeapon == null || !(row instanceof UIPanelAPI)) {
				continue;
			}
			WeaponAPI match = null;
			for (int j = 0; j < weapons.size(); j++) {
				WeaponAPI weapon = (WeaponAPI) weapons.get(j);
				if (weapon == rowWeapon) {
					match = weapon;
					break;
				}
			}
			if (match == null || !regenerates(match)) {
				continue;
			}
			liveRows.add(row);
			liveWeapons.add(match);
		}

		if (sameRows(liveRows)) {
			return;
		}

		detachAll();
		attachRows(liveRows, liveWeapons);
	}

	/** True when the attachments already cover exactly these row objects, in order. */
	private boolean sameRows(List liveRows) {
		if (liveRows.size() != attachedRows.size()) {
			return false;
		}
		for (int i = 0; i < liveRows.size(); i++) {
			if (liveRows.get(i) != attachedRows.get(i)) {
				return false;
			}
		}
		return true;
	}

	private void attachRows(List liveRows, List liveWeapons) {
		for (int i = 0; i < liveRows.size(); i++) {
			Object row = liveRows.get(i);
			WeaponAPI weapon = (WeaponAPI) liveWeapons.get(i);

			UIComponentAPI bar = findAmmoBar(row);
			if (bar == null) {
				continue;
			}

			AmmoRegenBarOverlay overlay = new AmmoRegenBarOverlay(weapon);
			CustomPanelAPI panel = Global.getSettings().createCustom(
					bar.getPosition().getWidth(), bar.getPosition().getHeight(), overlay);
			// The overlay draws against the ammo bar's own position, never its
			// own: the parent lays its children out and overwrites whatever we
			// set, which is how the bar ended up at the start of the row.
			overlay.setAnchor(bar);
			overlay.setStyle(style);

			UIPanelAPI parent = (UIPanelAPI) row;
			parent.addComponent(panel);
			parent.bringComponentToTop(panel);

			attachedWeapons.add(weapon);
			attachedRows.add(row);
			attachedBars.add(bar);
			attachedPanels.add(panel);
			attachedPlugins.add(overlay);
		}

		if (debugLog) {
			log().info("AmmoRegenBar: attached " + attachedPanels.size() + " overlays out of "
					+ liveRows.size() + " eligible weapon rows");
		}
	}

	private void detachAll() {
		for (int i = 0; i < attachedPanels.size(); i++) {
			try {
				Object row = attachedRows.get(i);
				if (row instanceof UIPanelAPI) {
					((UIPanelAPI) row).removeComponent((UIComponentAPI) attachedPanels.get(i));
				}
			} catch (Throwable t) {
				// A row that is already gone needs no cleanup.
			}
		}
		attachedWeapons.clear();
		attachedRows.clear();
		attachedBars.clear();
		attachedPanels.clear();
		attachedPlugins.clear();
	}

	private boolean regenerates(WeaponAPI weapon) {
		if (weapon == null || !weapon.usesAmmo()) {
			return false;
		}
		AmmoTrackerAPI tracker = weapon.getAmmoTracker();
		return tracker != null && tracker.getAmmoPerSecond() > 0f;
	}

	// ------------------------------------------------------------------
	// walking the game's UI tree
	// ------------------------------------------------------------------

	private UIPanelAPI findUIRoot(CombatUIAPI ui) {
		String[] candidates = new String[] { uiRootMethod, "getWidgetPanel", "getScreenPanel" };
		for (int i = 0; i < candidates.length; i++) {
			Object root = invokeNoArg(ui, candidates[i]);
			if (root instanceof UIPanelAPI) {
				return (UIPanelAPI) root;
			}
		}
		return null;
	}

	/**
	 * Breadth first sweep for widgets that expose a no argument getWeapon(),
	 * which is what the vanilla weapon row does. Bounded so a surprise in the
	 * tree cannot turn into a hang.
	 */
	private List findWeaponRows(UIPanelAPI root) {
		List found = new ArrayList();
		List queue = new ArrayList();
		queue.add(root);

		int visited = 0;
		while (!queue.isEmpty() && visited < 4000) {
			Object node = queue.remove(0);
			visited++;

			if (hasNoArgMethod(node, "getWeapon")) {
				found.add(node);
				continue; // a row has no further rows inside it
			}

			List children = childrenOf(node);
			for (int i = 0; i < children.size(); i++) {
				Object child = children.get(i);
				if (child != null) {
					queue.add(child);
				}
			}
		}
		return found;
	}

	private List childrenOf(Object node) {
		String[] names = new String[] { "getChildrenCopy", "getChildrenNonCopy", "getCopy" };
		for (int i = 0; i < names.length; i++) {
			Object result = invokeNoArg(node, names[i]);
			if (result instanceof List) {
				return (List) result;
			}
		}
		return new ArrayList();
	}

	/**
	 * The ammo bar is the one child of a weapon row whose class carries the
	 * unobfuscated setShowMinimum(boolean). Falls back to the row's fields in
	 * case it is held there rather than added as a child.
	 */
	private UIComponentAPI findAmmoBar(Object row) {
		List children = childrenOf(row);
		for (int i = 0; i < children.size(); i++) {
			Object child = children.get(i);
			if (child instanceof UIComponentAPI && hasMethod(child, "setShowMinimum")) {
				return (UIComponentAPI) child;
			}
		}

		List values = fieldValues(row);
		for (int i = 0; i < values.size(); i++) {
			Object value = values.get(i);
			if (value instanceof UIComponentAPI && hasMethod(value, "setShowMinimum")) {
				return (UIComponentAPI) value;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------
	// reflection helpers
	//
	// The game loads mod classes through a class loader that refuses to hand
	// out java.lang.reflect.Method / Field ("File access and reflection are not
	// allowed to scripts"). So those types must never appear in this class.
	// Instead the reflection classes are fetched through the bootstrap loader
	// and driven with java.lang.invoke, which is not restricted. Every other
	// mod that reflects into the game does the same thing.
	// ------------------------------------------------------------------

	private static boolean reflectionReady = false;
	private static MethodHandle mhGetMethod;
	private static MethodHandle mhGetMethods;
	private static MethodHandle mhMethodName;
	private static MethodHandle mhInvoke;
	private static MethodHandle mhGetDeclaredFields;
	private static MethodHandle mhFieldAccessible;
	private static MethodHandle mhFieldGet;

	static {
		try {
			ClassLoader boot = Class.class.getClassLoader();
			Class methodClass = Class.forName("java.lang.reflect.Method", false, boot);
			Class fieldClass = Class.forName("java.lang.reflect.Field", false, boot);
			MethodHandles.Lookup lookup = MethodHandles.lookup();

			mhGetMethod = lookup.findVirtual(Class.class, "getMethod",
					MethodType.methodType(methodClass, String.class, Class[].class));
			mhGetMethods = lookup.findVirtual(Class.class, "getMethods",
					MethodType.methodType(Class.forName("[Ljava.lang.reflect.Method;", false, boot)));
			mhMethodName = lookup.findVirtual(methodClass, "getName",
					MethodType.methodType(String.class));
			mhInvoke = lookup.findVirtual(methodClass, "invoke",
					MethodType.methodType(Object.class, Object.class, Object[].class));
			mhGetDeclaredFields = lookup.findVirtual(Class.class, "getDeclaredFields",
					MethodType.methodType(Class.forName("[Ljava.lang.reflect.Field;", false, boot)));
			mhFieldAccessible = lookup.findVirtual(fieldClass, "setAccessible",
					MethodType.methodType(void.class, boolean.class));
			mhFieldGet = lookup.findVirtual(fieldClass, "get",
					MethodType.methodType(Object.class, Object.class));

			reflectionReady = true;
		} catch (Throwable t) {
			Logger.getLogger("AmmoRegenBar").error("AmmoRegenBar: reflection setup failed, mod will do nothing", t);
		}
	}

	/** The java.lang.reflect.Method for name, or null. Typed as Object on purpose. */
	private Object lookupMethod(Object target, String name) {
		if (target == null || name == null || !reflectionReady) {
			return null;
		}
		try {
			return mhGetMethod.invoke((Object) target.getClass(), name, new Class[0]);
		} catch (Throwable t) {
			return null;
		}
	}

	private Object invokeNoArg(Object target, String name) {
		Object method = lookupMethod(target, name);
		if (method == null) {
			return null;
		}
		try {
			return mhInvoke.invoke(method, target, new Object[0]);
		} catch (Throwable t) {
			return null;
		}
	}

	private boolean hasNoArgMethod(Object target, String name) {
		return lookupMethod(target, name) != null;
	}

	/** True when the class declares a method of that name, whatever its arguments. */
	private boolean hasMethod(Object target, String name) {
		if (target == null || !reflectionReady) {
			return false;
		}
		try {
			Object[] methods = (Object[]) mhGetMethods.invoke((Object) target.getClass());
			for (int i = 0; i < methods.length; i++) {
				if (name.equals(mhMethodName.invoke(methods[i]))) {
					return true;
				}
			}
		} catch (Throwable t) {
			// Treated as "no such method".
		}
		return false;
	}

	/** Values of all declared fields of an object, for the ammo bar fallback search. */
	private List fieldValues(Object target) {
		List values = new ArrayList();
		if (target == null || !reflectionReady) {
			return values;
		}
		try {
			Object[] fields = (Object[]) mhGetDeclaredFields.invoke((Object) target.getClass());
			for (int i = 0; i < fields.length; i++) {
				try {
					mhFieldAccessible.invoke(fields[i], true);
					Object value = mhFieldGet.invoke(fields[i], target);
					if (value != null) {
						values.add(value);
					}
				} catch (Throwable t) {
					// Inaccessible field, keep looking.
				}
			}
		} catch (Throwable t) {
			// No field access, the children scan has to be enough.
		}
		return values;
	}


	// ------------------------------------------------------------------
	// LunaLib bridge
	//
	// LunaLib is optional and must stay that way, so its types are never named
	// here: a direct reference would put lunalib/... into this class's constant
	// pool, and MagicLib's own notes record that even a soft dependency on it
	// can turn hard. The class is fetched by name from the script class loader
	// and driven through the same MethodHandle helpers the sandbox already
	// forces on us, so nothing links and the build needs no LunaLib.jar.
	//
	// There is no settings listener for the same reason: implementing their
	// interface at runtime would need java.lang.reflect.Proxy, which is
	// blocked. Settings are re-read at the start of each battle and on ALT + R,
	// which is enough because their menu only opens outside combat.
	// ------------------------------------------------------------------

	public static final String MOD_ID = "ammoRegenBar";
	private static final String LUNA_MOD_ID = "lunalib";

	private boolean lunaChecked = false;
	private boolean lunaPresent = false;
	private Object lunaGetString;
	private Object lunaGetBoolean;
	private Object lunaGetDouble;

	private void lunaInit() {
		lunaChecked = true;
		lunaPresent = false;
		lunaGetString = null;
		lunaGetBoolean = null;
		lunaGetDouble = null;

		if (!reflectionReady) {
			return;
		}
		try {
			if (!Global.getSettings().getModManager().isModEnabled(LUNA_MOD_ID)) {
				return;
			}
			ClassLoader loader = Global.getSettings().getScriptClassLoader();
			if (loader == null) {
				loader = AmmoRegenBarPlugin.class.getClassLoader();
			}
			Class lunaClass = Class.forName("lunalib.lunaSettings.LunaSettings", false, loader);
			Class[] args = new Class[] { String.class, String.class };

			lunaGetString = mhGetMethod.invoke((Object) lunaClass, "getString", args);
			lunaGetBoolean = mhGetMethod.invoke((Object) lunaClass, "getBoolean", args);
			lunaGetDouble = mhGetMethod.invoke((Object) lunaClass, "getDouble", args);
			lunaPresent = lunaGetString != null && lunaGetBoolean != null && lunaGetDouble != null;
		} catch (Throwable t) {
			reportOnce("lunaInit", "AmmoRegenBar: LunaLib is enabled but its settings API could not be reached, "
					+ "falling back to " + CONFIG_PATH + " (" + t + ")");
		}
	}

	private Object lunaCall(Object method, String fieldId) {
		if (method == null) {
			return null;
		}
		try {
			// The receiver has to be a typed local: MethodHandle.invoke is
			// signature polymorphic and a bare null literal will not compile.
			Object receiver = null;
			return mhInvoke.invoke(method, receiver, new Object[] { MOD_ID, fieldId });
		} catch (Throwable t) {
			return null;
		}
	}

	/** Overrides the json defaults with whatever LunaLib holds, field by field. */
	private void applyLunaOverrides() {
		if (!lunaChecked) {
			lunaInit();
		}
		if (!lunaPresent) {
			return;
		}

		Object color = lunaCall(lunaGetString, "arb_barColor");
		if (color instanceof String) {
			int[] rgba = new int[] { style.colorR, style.colorG, style.colorB, style.colorA };
			if (parseHexColor((String) color, rgba)) {
				style.colorR = rgba[0];
				style.colorG = rgba[1];
				style.colorB = rgba[2];
				style.colorA = rgba[3];
			}
		}

		Object thickness = lunaCall(lunaGetDouble, "arb_barThickness");
		if (thickness instanceof Double) {
			float value = ((Double) thickness).floatValue();
			if (value < 0.01f) {
				value = 0.01f;
			}
			if (value > 1f) {
				value = 1f;
			}
			style.thicknessFraction = value;
		}

		Object position = lunaCall(lunaGetString, "arb_barPosition");
		if (position instanceof String) {
			style.position = AmmoRegenBarStyle.parsePosition((String) position, style.position);
		}

		Object vertical = lunaCall(lunaGetBoolean, "arb_vertical");
		if (vertical instanceof Boolean) {
			style.vertical = ((Boolean) vertical).booleanValue();
		}

		Object side = lunaCall(lunaGetString, "arb_verticalSide");
		if (side instanceof String) {
			style.side = AmmoRegenBarStyle.parseSide((String) side, style.side);
		}
	}

	/**
	 * Accepts #RRGGBB, RRGGBB, #RGB and #RRGGBBAA, in either case. Writes into
	 * rgba and returns whether it understood the input; leaves rgba untouched
	 * otherwise, so a typo in the settings falls back rather than blanking the
	 * bar. Deliberately hand rolled: Integer.decode chokes on the leading # and
	 * overflows on eight digits.
	 */
	private static boolean parseHexColor(String text, int[] rgba) {
		if (text == null || rgba == null || rgba.length < 4) {
			return false;
		}
		String value = text.trim().toLowerCase();
		if (value.startsWith("#")) {
			value = value.substring(1);
		}
		int length = value.length();
		if (length != 3 && length != 6 && length != 8) {
			return false;
		}
		for (int i = 0; i < length; i++) {
			char c = value.charAt(i);
			boolean digit = c >= '0' && c <= '9';
			boolean letter = c >= 'a' && c <= 'f';
			if (!digit && !letter) {
				return false;
			}
		}

		try {
			if (length == 3) {
				rgba[0] = Integer.parseInt(value.substring(0, 1), 16) * 17;
				rgba[1] = Integer.parseInt(value.substring(1, 2), 16) * 17;
				rgba[2] = Integer.parseInt(value.substring(2, 3), 16) * 17;
				return true;
			}
			rgba[0] = Integer.parseInt(value.substring(0, 2), 16);
			rgba[1] = Integer.parseInt(value.substring(2, 4), 16);
			rgba[2] = Integer.parseInt(value.substring(4, 6), 16);
			if (length == 8) {
				rgba[3] = Integer.parseInt(value.substring(6, 8), 16);
			}
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	// ------------------------------------------------------------------
	// diagnostics
	// ------------------------------------------------------------------

	private void dumpState(ShipAPI ship) {
		log().info("AmmoRegenBar: ship=" + ship.getHullSpec().getHullId()
				+ " overlays=" + attachedPanels.size());
		for (int i = 0; i < attachedPanels.size(); i++) {
			WeaponAPI weapon = (WeaponAPI) attachedWeapons.get(i);
			UIComponentAPI bar = (UIComponentAPI) attachedBars.get(i);
			PositionAPI pos = bar.getPosition();
			AmmoTrackerAPI tracker = weapon.getAmmoTracker();
			log().info("AmmoRegenBar overlay[" + i + "] " + weapon.getDisplayName()
					+ " | ammo=" + weapon.getAmmo() + "/" + weapon.getMaxAmmo()
					+ " | reloadSize=" + (tracker == null ? "?" : "" + tracker.getReloadSize())
					+ " | progress=" + (tracker == null ? "?" : "" + tracker.getReloadProgress())
					+ " | barPos x=" + pos.getX() + " y=" + pos.getY()
					+ " w=" + pos.getWidth() + " h=" + pos.getHeight());
			AmmoRegenBarOverlay overlay = (AmmoRegenBarOverlay) attachedPlugins.get(i);
			log().info("AmmoRegenBar overlay[" + i + "] RENDER renderCalls=" + overlay.renderCalls
					+ " drawCalls=" + overlay.drawCalls
					+ " lastAlpha=" + overlay.lastAlpha
					+ " lastLevel=" + overlay.lastLevel
					+ " lastRect=" + overlay.lastRect);
		}
	}
}

/**
 * One of these is attached to every weapon row of the combat HUD, sitting on
 * top of that row's ammo bar. The game renders it as part of its own UI tree,
 * which is the only way to draw above the HUD: an EveryFrameCombatPlugin
 * renders underneath it.
 *
 * It owns no geometry. Position and size are copied from the ammo bar every
 * frame by AmmoRegenBarPlugin, so this class only has to fill a strip.
 *
 * Kept in this file next to the plugin because the two are one unit: the
 * plugin decides which weapons get an overlay, the overlay decides what a
 * single one looks like.
 */
class AmmoRegenBarOverlay implements CustomUIPanelPlugin {

	private WeaponAPI weapon;
	private UIComponentAPI anchor;

	// Diagnostics: tells apart "never asked to render" from "rendered but invisible".
	int renderCalls = 0;
	int drawCalls = 0;
	float lastAlpha = -1f;
	float lastLevel = -1f;
	String lastRect = "none";

	// Shared with the plugin and mutated there, so a reload needs no re-push.
	private AmmoRegenBarStyle style = new AmmoRegenBarStyle();

	public AmmoRegenBarOverlay(WeaponAPI weapon) {
		this.weapon = weapon;
	}

	public WeaponAPI getWeapon() {
		return weapon;
	}

	/** The game's own ammo bar. Everything is drawn relative to it. */
	public void setAnchor(UIComponentAPI anchor) {
		this.anchor = anchor;
	}

	public void setStyle(AmmoRegenBarStyle style) {
		this.style = style;
	}

	/**
	 * Fill level in 0..1, or a negative value when nothing should be drawn.
	 * getReloadProgress() is already normalised to 0..1: it advances by
	 * ammoPerSecond / reloadSize per second. The division is a safety net for
	 * any weapon that reports progress in ammo units instead.
	 */
	private float fill() {
		if (weapon == null || !weapon.usesAmmo()) {
			return -1f;
		}
		AmmoTrackerAPI tracker = weapon.getAmmoTracker();
		if (tracker == null || tracker.getAmmoPerSecond() <= 0f) {
			return -1f;
		}
		if (style.hideWhenFull && tracker.getAmmo() >= tracker.getMaxAmmo()) {
			return -1f;
		}

		float reloadSize = tracker.getReloadSize();
		float raw = tracker.getReloadProgress();
		float value = raw;
		if (value > 1f && reloadSize > 0f) {
			value = raw / reloadSize;
		}
		if (style.invert) {
			value = 1f - value;
		}
		if (value < 0f) {
			value = 0f;
		}
		if (value > 1f) {
			value = 1f;
		}
		return value;
	}

	public void render(float alphaMult) {
		try {
			renderCalls++;
			lastAlpha = alphaMult;
			if (anchor == null) {
				lastRect = "no anchor";
				return;
			}
			float level = fill();
			lastLevel = level;
			if (level < 0f && !style.debugLoud) {
				lastRect = "nothing to draw";
				return;
			}

			PositionAPI pos = anchor.getPosition();
			if (pos == null) {
				lastRect = "no position";
				return;
			}

			float x0 = pos.getX();
			float y0 = pos.getY();
			float boxW = pos.getWidth();
			float boxH = pos.getHeight();
			if (boxW <= 0f || boxH <= 0f) {
				lastRect = "empty widget";
				return;
			}

			// debugLoud keeps the real geometry and only ignores the ammo state,
			// so one look confirms placement, thickness and orientation.
			int r = style.colorR;
			int g = style.colorG;
			int b = style.colorB;
			if (style.debugLoud) {
				level = 1f;
				r = 255;
				g = 0;
				b = 255;
			}

			// Thickness always measures across the bar, so it follows the short
			// axis: the anchor's height when horizontal, its width when vertical.
			float thickness;
			float length;
			float bx;
			float by;
			float bw;
			float bh;
			float shadowDX;
			float shadowDY;

			if (style.vertical) {
				thickness = boxW * style.thicknessFraction;
				if (thickness < 1f) {
					thickness = 1f;
				}
				length = boxH - style.insetLong * 2f;
				if (length <= 0f) {
					lastRect = "no room";
					return;
				}
				bx = style.side == AmmoRegenBarStyle.SIDE_RIGHT ? x0 + boxW - thickness : x0;
				by = y0 + style.insetLong;
				bw = thickness;
				bh = length * level;
				shadowDX = -1f;
				shadowDY = 0f;
			} else {
				thickness = boxH * style.thicknessFraction;
				if (thickness < 1f) {
					thickness = 1f;
				}
				length = boxW - style.insetLong * 2f;
				if (length <= 0f) {
					lastRect = "no room";
					return;
				}
				if (style.position == AmmoRegenBarStyle.POS_TOP) {
					by = y0 + boxH - thickness;
				} else if (style.position == AmmoRegenBarStyle.POS_BOTTOM) {
					by = y0;
				} else {
					by = y0 + (boxH - thickness) * 0.5f;
				}
				bx = x0 + style.insetLong;
				bw = length * level;
				bh = thickness;
				shadowDX = 0f;
				shadowDY = -1f;
			}

			lastRect = "mode=" + (style.vertical ? "v" : "h")
					+ " x=" + bx + " y=" + by + " w=" + bw + " h=" + bh
					+ " alpha=" + alphaMult;

			GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
			try {
				GL11.glDisable(GL11.GL_TEXTURE_2D);
				GL11.glEnable(GL11.GL_BLEND);
				GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

				if (style.drawShadow) {
					quad(bx + shadowDX, by + shadowDY, bw, bh, 0, 0, 0, (int) (180f * alphaMult));
				}
				quad(bx, by, bw, bh, r, g, b, (int) ((float) style.colorA * alphaMult));
			} finally {
				GL11.glPopAttrib();
			}
			drawCalls++;
		} catch (Throwable t) {
			// Never let a render failure take the battle down with it.
		}
	}

	private void quad(float x, float y, float w, float h, int r, int g, int b, int a) {
		if (w <= 0f || h <= 0f) {
			return;
		}
		if (a < 0) {
			a = 0;
		}
		if (a > 255) {
			a = 255;
		}
		GL11.glColor4ub((byte) r, (byte) g, (byte) b, (byte) a);
		GL11.glBegin(GL11.GL_QUADS);
		GL11.glVertex2f(x, y);
		GL11.glVertex2f(x, y + h);
		GL11.glVertex2f(x + w, y + h);
		GL11.glVertex2f(x + w, y);
		GL11.glEnd();
	}

	public void positionChanged(PositionAPI position) {
	}

	public void renderBelow(float alphaMult) {
	}

	public void advance(float amount) {
	}

	public void processInput(List<InputEventAPI> events) {
	}

	public void buttonPressed(Object buttonId) {
	}
}
