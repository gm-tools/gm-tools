package gmtools.common;

/**
 * copyright (c) 2014 Alexander E.I. Brownlee (sbr@cs.stir.ac.uk) Released under the MIT Licence
 * http://opensource.org/licenses/MIT Instructions, citation information,
 * licencing and source are available at https://github.com/gm-tools/gm-tools/
 */
public class Maths {
	public static final double roundDouble(double d, int places) {
		return Math.round(d * Math.pow(10, (double) places))
				/ Math.pow(10, (double) places);
	}

	public static final double normalise(double value, double min, double max) {
		return (value - min) / (max - min);
	}

	/** @return x,y */
	public static double[] nearestPointOnLine(double pointX, double pointY, double endLine1X, double endLine1Y, double endLine2X, double endLine2Y) {
		// get eqn of line, get eqn of perpendicular line from it to the point,
		// find intersection

		// calc eqn of line
		double mLine = (endLine2Y - endLine1Y) / (endLine2X - endLine1X);
		double cLine = endLine1Y - (mLine * endLine1X);

		// eqn of line to point
		double mLine2 = -1 / mLine; // perpendicular lines have m1m2=-1, so new
									// gradient is -1/m1
		double cLine2 = pointY - (mLine2 * pointX);

		// intersection X (x=c1-c2 / m2-m1)
		double intX = (cLine - cLine2) / (mLine2 - mLine);
		double intY = (mLine2 * intX) + cLine2;

		return new double[] { intX, intY };
	}

	public static double euclideanDistance(double... ds) {
		double sum = 0;
		for (double d : ds) {
			sum += Math.pow(d, 2.0);
		}

		return Math.sqrt(sum);
	}
}
