package ammoregenbar;

/**
 * Everything that decides how the bar looks, in one mutable object.
 *
 * The plugin owns a single instance and edits it in place; every overlay holds
 * a reference to that same instance, which is what makes a config reload apply
 * to bars that are already attached.
 *
 * It is a separate class rather than a longer setStyle() signature: passing
 * thirteen positional values, five of them consecutive booleans, is an argument
 * transposition waiting to happen.
 */
class AmmoRegenBarStyle {

	static final int POS_TOP = 0;
	static final int POS_CENTER = 1;
	static final int POS_BOTTOM = 2;

	static final int SIDE_LEFT = 0;
	static final int SIDE_RIGHT = 1;

	/**
	 * Thickness as a fraction of the anchor's cross axis: its height for a
	 * horizontal bar, its width for a vertical one.
	 */
	float thicknessFraction = 0.3333f;

	/** Inset at both ends of the long axis. */
	float insetLong = 0f;

	boolean invert = false;
	boolean hideWhenFull = true;
	boolean drawShadow = true;
	boolean debugLoud = false;

	boolean vertical = false;
	int position = POS_CENTER;
	int side = SIDE_LEFT;

	int colorR = 255;
	int colorG = 160;
	int colorB = 0;
	int colorA = 255;

	static int parsePosition(String text, int fallback) {
		if (text == null) {
			return fallback;
		}
		String value = text.trim().toLowerCase();
		if (value.equals("top")) {
			return POS_TOP;
		}
		if (value.equals("center")) {
			return POS_CENTER;
		}
		if (value.equals("bottom")) {
			return POS_BOTTOM;
		}
		return fallback;
	}

	static int parseSide(String text, int fallback) {
		if (text == null) {
			return fallback;
		}
		String value = text.trim().toLowerCase();
		if (value.equals("left")) {
			return SIDE_LEFT;
		}
		if (value.equals("right")) {
			return SIDE_RIGHT;
		}
		return fallback;
	}

	String describe() {
		String pos = position == POS_TOP ? "top" : (position == POS_BOTTOM ? "bottom" : "center");
		String sideName = side == SIDE_RIGHT ? "right" : "left";
		return "vertical=" + vertical
				+ " position=" + pos
				+ " side=" + sideName
				+ " thickness=" + thicknessFraction
				+ " color=" + hex();
	}

	String hex() {
		return "#" + two(colorR) + two(colorG) + two(colorB);
	}

	private static String two(int value) {
		String s = Integer.toHexString(value & 0xff);
		return s.length() < 2 ? "0" + s : s;
	}
}
