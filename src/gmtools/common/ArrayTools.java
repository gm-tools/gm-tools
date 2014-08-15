package gmtools.common;

/**
 * copyright (c) 2014 Alexander E.I. Brownlee (sbr@cs.stir.ac.uk)
 * Released under the MIT Licence http://opensource.org/licenses/MIT
 * Instructions, citation information, licencing and source
 * are available at https://github.com/gm-tools/gm-tools/
 */
public class ArrayTools {
	/**
	 * Set this to fix the decimal places that values are rounded to
	 * (ArrayTools.NOROUNDING for no rounding)
	 */
	public static int roundPlaces = 2;
	public final static int NOROUNDING = -1;
	public final static String SEPARATOR = " ";

	public static String toString(double[] array) {
		return toString(array, SEPARATOR);
	}

	public static String toString(double[] array, String separator) {
		StringBuffer rval = new StringBuffer();

		boolean first = true;
		for (int i = 0; i < array.length; i++) {
			if (!first) {
				rval.append(separator);
			}

			if (roundPlaces > -1) {
				rval.append(Maths.roundDouble(array[i], roundPlaces));
			} else {
				rval.append(array[i]);
			}

			first = false;
		}

		return rval.toString();
	}

	public static String toString(String[] array) {
		return toString(array, SEPARATOR);
	}

	public static String toString(String[] array, String separator) {
		StringBuffer rval = new StringBuffer();

		boolean first = true;
		for (int i = 0; i < array.length; i++) {
			if (!first) {
				rval.append(separator);
			}

			rval.append(array[i]);
			first = false;
		}

		return rval.toString();
	}

	public static String toString(int[] array) {
		return toString(array, SEPARATOR);
	}

	public static String toString(int[] array, String separator) {
		StringBuffer rval = new StringBuffer();

		boolean first = true;
		for (int i = 0; i < array.length; i++) {
			if (!first) {
				rval.append(separator);
			}

			rval.append(array[i]);
			first = false;
		}

		return rval.toString();
	}

	public static String toString(long[] array) {
		return toString(array, SEPARATOR);
	}

	public static String toString(long[] array, String separator) {
		StringBuffer rval = new StringBuffer();

		boolean first = true;
		for (int i = 0; i < array.length; i++) {
			if (!first) {
				rval.append(separator);
			}

			rval.append(array[i]);
			first = false;
		}

		return rval.toString();
	}

	public static String toString(boolean[] array) {
		StringBuffer rval = new StringBuffer();
		for (int i = 0; i < array.length; i++)
			rval.append((array[i] ? 1 : 0));
		return rval.toString();
	}

	public static String toString(Object[] array) {
		StringBuffer rval = new StringBuffer();
		for (int i = 0; i < array.length; i++)
			rval.append(array[i]);
		return rval.toString();
	}
	
	public static String toString(Object[] array, String separator) {
		StringBuffer rval = new StringBuffer();

		boolean first = true;
		for (int i = 0; i < array.length; i++) {
			if (!first) {
				rval.append(separator);
			}

			rval.append(array[i]);
			first = false;
		}

		return rval.toString();
	}
}
