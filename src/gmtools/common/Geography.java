package gmtools.common;

import uk.me.jstott.jcoord.LatLng;
import uk.me.jstott.jcoord.UTMRef;
import gmtools.graph.TaxiEdge;
import gmtools.graph.TaxiNode;

/**
 * copyright (c) 2014-2015 Alexander E.I. Brownlee (sbr@cs.stir.ac.uk)
 * Released under the MIT Licence http://opensource.org/licenses/MIT
 * Instructions, citation information, licencing and source
 * are available at https://github.com/gm-tools/gm-tools/
 */
public class Geography {
	private static final double DISTANCE_THRESHOLD_FOR_ZERO_M = 0.1; // equal LatLng value can return distances that are non-zero

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

	/**gets bearing for an edge (angle from north) when travelling from->to*/
	public static double bearing(TaxiEdge edge) {
		double lon1 = edge.getTnFrom().getLonCoordinate();
		double lon2 = edge.getTnTo().getLonCoordinate();
		double lat1 = Math.toRadians(edge.getTnFrom().getLatCoordinate());
		double lat2 = Math.toRadians(edge.getTnTo().getLatCoordinate());
		double lonDiff = Math.toRadians(lon2 - lon1);
		double y = Math.sin(lonDiff) * Math.cos(lat2);
		double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(lonDiff);
		
		return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
	}

	/**
	 * @return NaN if the two edges are not connected
	 */
	public static double angleBetweenEdges(TaxiEdge te1, TaxiEdge te2) {
		TaxiNode start;
		TaxiNode end;
		TaxiNode shared;
		if (te1.getTnFrom() == te2.getTnFrom()) {
			shared = te1.getTnFrom();
			start = te1.getTnTo();
			end = te2.getTnTo();
		} else if (te1.getTnFrom() == te2.getTnTo()) {
			shared = te1.getTnFrom();
			start = te1.getTnTo();
			end = te2.getTnFrom();
		} else if (te1.getTnTo() == te2.getTnFrom()) {
			shared = te1.getTnTo();
			start = te1.getTnFrom();
			end = te2.getTnTo();
		} else if (te1.getTnTo() == te2.getTnTo()) {
			shared = te1.getTnTo();
			start = te1.getTnFrom();
			end = te2.getTnFrom();
		} else { // no shared node
			return Double.NaN;
		}
		
		return Geography.angleBetweenNodes(start, shared, end);
	}

	/**
	 * @return distance in metres
	 */
	public static double distance(LatLng ll1, LatLng ll2) {
		double d = ll1.distance(ll2) * 1000;
		if (d < DISTANCE_THRESHOLD_FOR_ZERO_M) {
			d = 0;
		}
		
		return d;
	}

	/**
	 * @return distance in metres
	 */
	public static double distance(double lat1, double lon1, double lat2, double lon2) {
		LatLng llFrom = new LatLng(lat1, lon1);
		LatLng llTo = new LatLng(lat2, lon2);
		
		double d = distance(llFrom, llTo);
		
		return d;
	}

	/**
	 * @return distance in metres
	 */
	public static double distance(TaxiNode tnFrom, TaxiNode tnTo) {
		return distance(tnFrom.getLatCoordinate(), tnFrom.getLonCoordinate(), tnTo.getLatCoordinate(), tnTo.getLonCoordinate());
	}

	/**
	 * Takes three nodes - previous, current, and next - and gives the turning angle required at the current node to go from prev to next
	 */
	public static double angleBetweenNodes(TaxiNode prev, TaxiNode current, TaxiNode next) {
		// convert to utm coords to allow 2D maths
		LatLng llprev = new LatLng(prev.getLatCoordinate(), prev.getLonCoordinate());
		LatLng llnext = new LatLng(next.getLatCoordinate(), next.getLonCoordinate());
		LatLng llcurrent = new LatLng(current.getLatCoordinate(), current.getLonCoordinate());
		
		return Maths.roundDouble(Geography.angleBetweenPoints(llprev, llcurrent, llnext), 2);
	}

	public static double angleBetweenPoints(LatLng llprev, LatLng llcurrent, LatLng llnext) {
		// convert to utm coords to allow 2D maths
		UTMRef utmPrev = llprev.toUTMRef();
		UTMRef utmCurrent = llcurrent.toUTMRef();
		UTMRef utmNext = llnext.toUTMRef();
		double prevX = utmPrev.getEasting();
		double prevY = utmPrev.getNorthing();
		double currentX = utmCurrent.getEasting();
		double currentY = utmCurrent.getNorthing();
		double nextX = utmNext.getEasting();
		double nextY = utmNext.getNorthing();
	
		double angle1 = Math.atan2(currentY - prevY, currentX - prevX);
		double angle2 = Math.atan2(nextY - currentY, nextX - currentX);
		
		double diff = angle1 - angle2;
		double deg = Math.abs(Math.toDegrees(diff));
		if (deg > 180) {
			deg = 360 - deg;
		}
		
		return deg;
	}
}
