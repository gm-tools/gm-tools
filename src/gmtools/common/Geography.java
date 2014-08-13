package gmtools.common;

/**
 * copyright (c) 2014 Alexander E.I. Brownlee
 * Released under the MIT Licence http://opensource.org/licenses/MIT
 * Instructions, citation information, licencing and source
 * are available at https://github.com/gm-tools/gm-tools/
 */
public class Geography {
	/**
	 * @param lat degrees, minutes, seconds, fractionalSeconds, direction DDDMMSS.SSd e.g. 0532138.77N
	 * @param lon as for lat
	 * @return 2 element array: lat lon in decimal
	 */
	public static double[] latLonToDecimal (String strLat, String strLon) {
		strLat = strLat.toUpperCase();
		strLon = strLon.toUpperCase();
		
		// already decimal?
		if (!(strLat.contains("N") || strLat.contains("S")) && !(strLon.contains("E") || strLon.contains("W"))) {
			return new double[] {Double.parseDouble(strLat),Double.parseDouble(strLon)};
		}
		
		boolean letterFirst = strLat.startsWith("N") || strLat.startsWith("S");
		
		// work from the right, as there will always be 2 digits for mins and secs, but maybe 1-3 for degrees
		String strLatS = strLat.substring(strLat.indexOf('.') - 2, strLat.length() - (letterFirst ? 1 : 2)); // 2 digits on left of dec pt, and stop before N/S at end if it exists
		String strLatM = strLat.substring(strLat.indexOf('.') - 4, strLat.indexOf('.') - 2);
		String strLatD = strLat.substring((letterFirst ? 1 : 0), strLat.indexOf('.') - 4);
		double latS = Double.parseDouble(strLatS);
		double latM = Double.parseDouble(strLatM);
		double latD = Double.parseDouble(strLatD);
		double lat = latD + (latM / 60) + (latS / 3600);		
		
		if ((strLat.substring(strLat.length() - 1).equals("S")) || strLat.startsWith("S")) {
			lat = lat * -1;
		}
		
		String strlonS = strLon.substring(strLon.indexOf('.') - 2, strLon.length() - (letterFirst ? 1 : 2)); // 2 digits on left of dec pt, and stop before E/W at end if it exists
		String strlonM = strLon.substring(strLon.indexOf('.') - 4, strLon.indexOf('.') - 2);
		String strlonD = strLon.substring((letterFirst ? 1 : 0), strLon.indexOf('.') - 4);
		double lonS = Double.parseDouble(strlonS);
		double lonM = Double.parseDouble(strlonM);
		double lonD = Double.parseDouble(strlonD);
		double lon = lonD + (lonM / 60) + (lonS / 3600);		
		
		if (strLon.substring(strLon.length() - 1).equals("W") || strLon.startsWith("W")) {
			lon = lon * -1;
		}
		
		return new double[] {lat, lon};
	}
}
