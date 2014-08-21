package gmtools.snaptracks;

import gmtools.common.ArrayTools;
import gmtools.common.Geography;
import gmtools.common.KMLUtils;
import gmtools.common.Sets;
import gmtools.parsers.ColumnIndices;
import gmtools.parsers.RawFlightTrackData;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import uk.me.jstott.jcoord.LatLng;
import de.micromata.opengis.kml.v_2_2_0.Document;
import de.micromata.opengis.kml.v_2_2_0.Kml;
import de.micromata.opengis.kml.v_2_2_0.KmlFactory;
import de.micromata.opengis.kml.v_2_2_0.LineString;
import de.micromata.opengis.kml.v_2_2_0.Style;

/**
 * copyright (c) 2014 Alexander E.I. Brownlee (sbr@cs.stir.ac.uk)
 * Released under the MIT Licence http://opensource.org/licenses/MIT
 * Instructions, citation information, licencing and source
 * are available at https://github.com/gm-tools/gm-tools/
 */
public class CleaningRawDataOutliers {
	/**
	 * Originally conceived as a separate tool, this is now called from SnapTracks
	 * usage: CleaningRawFR24DataOutliers inFile outFile [args...]
	 * args:
	 *     -m=0.8 : maximum fraction of points in a track that we'll try to fix (more than this and we'll give up) (default=0.8)
	 *     -M=15 : maximum number of points in a track that we'll try to fix (more than this and we'll give up) (default=unlimited) (over 30 seems very slow - an hour or two to process the track, plus several GB of heap needed to store the intermediate results)
	 *     -d=n : max distance from airport in km (default is 10)
	 *     -alat=x : lat of airport
	 *     -alon=y : lon of airport
	 *     Not yet implemented: -speeds=a:b,c:d,e:f... : pairs of values defining speed limits for turns. These are MaxTurningAngleDegrees:MaxSpeedMperS
	 */
	public static void main(String[] args) {
		// first, read file
		List<List<PointInTrack>> flightTracksOriginal = new ArrayList<List<PointInTrack>>();
		List<List<PointInTrack>> flightTracksUpdated = new ArrayList<List<PointInTrack>>();
		List<String> flightTracksComments = new ArrayList<String>();
		String fileNameIn = null;
		String fileNameOut = null;

		int elementsPerPointInTrack = 4;// currently 4 for historical data, 3 for live (which is missing timings so can't be used here anyway)
		int maxBadPoints = Integer.MAX_VALUE; // might want to limit this for speed (if over 30 then it gets very slow)
		double maxFractionBad = 0.8; // maximum fraction of points allowed to be bad before giving up
		boolean debug = false;
		double latAirport = Double.NaN;
		double lonAirport = Double.NaN;
		double maxDistanceFromAirportInKM = 10;
		double maxAltitudeInM = 2000; // points with altitude higher than this will be omitted from the output
		
		if (args.length < 2) {
			System.err.println("usage: CleaningRawDataOutliers inFile outFile [args...]");
			System.exit(1);
		}
		
		fileNameIn = args[0];
		fileNameOut = args[1];
		boolean argsOK = true;
		for (int i = 2; i < args.length; i++) {
			String a = args[i];
			
			try {
				if (a.startsWith("-alat=")) {
					latAirport = Double.parseDouble(a.substring(6));
				} else if (a.startsWith("-alon=")) {
					lonAirport = Double.parseDouble(a.substring(6));
				} else if (a.startsWith("-d=")) {
					maxDistanceFromAirportInKM = Double.parseDouble(a.substring(3));
				} else if (a.startsWith("-M=")) {
					maxBadPoints = Integer.parseInt(a.substring(3));
				} else if (a.startsWith("-m=")) {
					maxFractionBad = Double.parseDouble(a.substring(3));
				}
			} catch (Exception e) {
				System.err.println("Error parsing argument " + a);
				e.printStackTrace();
				argsOK = false;
			}
		}
		if (Double.isNaN(latAirport) || Double.isNaN(lonAirport)) {
			System.err.println("Please specify airport coordinates using -alat and -alon");
			System.exit(1);
		}
		
		if (!argsOK) {
			System.err.println("Exiting.");
			System.exit(1);
		}
		
		System.out.println("Cleaning with params:");
		System.out.println("  Input file: " + fileNameIn);
		System.out.println("  Output file: " + fileNameOut);
		System.out.println("  Airport lat, lon: " + latAirport + ", " + lonAirport);
		System.out.println("  Max distance from airport KM: " + maxDistanceFromAirportInKM);
		System.out.println("  Max bad points: " + maxBadPoints);
		System.out.println("  Max fraction bad: " + maxFractionBad);
		System.out.println();
		
		LatLng llMan = new LatLng(latAirport, lonAirport);
		int countRaw = 0;
		int countCleaned = 0;
		int countPointsRemoteToAirportRemoved = 0;
		int countUncleanable = 0;
		int countGoodTracks = 0;
		int countEmptyTracks = 0;
		int countTotalInOutput = 0;
		
		try {
			BufferedReader in = new BufferedReader(new FileReader(fileNameIn));
			PrintStream out = new PrintStream(new FileOutputStream(fileNameOut));
			String line = in.readLine();
			out.println(line); // copy header to output file
			String[] header = line.split(RawFlightTrackData.SEPARATOR);
			ColumnIndices columnIndices = new ColumnIndices(header, fileNameIn);
			while ((line = in.readLine()) != null) {
				countRaw++;
				//debug = (flightTracksOriginal.size() == 1488);
				if (debug) System.out.println("AC" + flightTracksOriginal.size());
				boolean trackGood;
				String[] cols = line.split(RawFlightTrackData.SEPARATOR);
				String[] track = cols[columnIndices.getColumnIndex(RawFlightTrackData.HEADER_TRACK, true)].split(RawFlightTrackData.SEPARATOR_COORDS);
				List<PointInTrack> trackOriginal = new ArrayList<PointInTrack>(track.length / elementsPerPointInTrack);
				List<PointInTrack> trackUpdated = new ArrayList<PointInTrack>(track.length / elementsPerPointInTrack);
				flightTracksOriginal.add(trackOriginal);
				flightTracksUpdated.add(trackUpdated);

				String comment;
				if (track.length >= elementsPerPointInTrack) {
					boolean rawTrackUnaltered = true;
					for (int i = 0; i < track.length; i+=elementsPerPointInTrack) {
						double altitude = Double.parseDouble(track[i+2]);
						if (altitude < maxAltitudeInM) { // used to just look for zero altitude - no longer checked - we want the altitude to distinguish separate visits by the same aircraft 
							LatLng point = new LatLng(Double.parseDouble(track[i]), Double.parseDouble(track[i+1]));
							double time = -1 * Double.parseDouble(track[i+3]);
							PointInTrack pit = new PointInTrack(point, time, altitude);
							
							if (Geography.distance(point, llMan) < (maxDistanceFromAirportInKM * 1000.0)) { // within 10km of airport (this means we omit data for GM at other airports)
								trackOriginal.add(pit);
							} else { // end of check for distance from airport
								rawTrackUnaltered = false; // we've dropped some points
							}
						} // end of check for altitude
					} // end of loop over elements in track
					
					if (!rawTrackUnaltered) {
						countPointsRemoteToAirportRemoved++;
					}
					
					// step 1-6.
					ExtremePointDecisionCache cache = new ExtremePointDecisionCache();
					List<Integer> badPointsInOriginal = getIndicesWithBadSpeedAndAngle(trackOriginal, cache, debug);
					if (badPointsInOriginal.isEmpty()) {
						if (debug) System.out.println("All good");
						comment = "good-no cleaning";
						trackUpdated.addAll(trackOriginal);
						countGoodTracks++;
						trackGood = true;
					} else {
						if (debug) System.out.println("AC" + (flightTracksOriginal.size()-1) + " bad points:" + ArrayTools.toString(badPointsInOriginal.toArray()));
						trackGood = false;
						
						// step 7 onward.
						// now, we'll try removing the bad points and see which removals are needed to make a route 
						// that complies with the speed/angle limits
						// we slowly increase the number of points being removed to avoid removing any unnecessarily
						boolean done = false;
						List<Integer> badPoints = new ArrayList<Integer>(badPointsInOriginal);
						List<PointInTrack> badTrack = new ArrayList<PointInTrack>(trackOriginal);
						while (!done && !badPoints.isEmpty() && (badPoints.size() < Math.min(maxBadPoints, (badTrack.size() * maxFractionBad)))) { // stop if we have found a valid track, or if there are too many bad points (>80% of points are bad)
							if (debug) System.out.println("AC" + (flightTracksOriginal.size()-1) + " still has " + badPoints.size() + " bad points out of " + badTrack.size());
							for (int numberToRemove = 1; !done && (numberToRemove <= badPoints.size()); numberToRemove++) {
								if (debug) System.out.println(numberToRemove + "/" + badPoints.size());
								// get the possible indices to remove
								int[][] allToRemove = Sets.nChooseKSets(badPoints.size(), numberToRemove);
								
								for (int removalIndex = 0; !done && (removalIndex < allToRemove.length); removalIndex++) {
									int[] toRemove = allToRemove[removalIndex]; // these will be indices in to the badPoints list
									
									// step 13. generate a new track with the appropriate points removed
									if (debug) System.out.print("removing:");
									int currentIndexInPointsToRemove = 0;
									for (int i = 0; i < badTrack.size(); i++) {
										int indexToRemove = badPoints.get(toRemove[currentIndexInPointsToRemove]);
										if (indexToRemove == i) { // done this way for speed, as toRemove will be in ascending order
											if (debug) System.out.print(" " + i);
											// if we've dropped a point, add to the time interval for the next point so the speed in the gap is still right
											// ("next point" in time is actually previous point in the list in FR24 format)
											if (trackUpdated.size() > 0) { // don't do this for the last point in time (ie first point in the list)
												trackUpdated.get(trackUpdated.size() - 1).timeSinceLastPoint += badTrack.get(i).timeSinceLastPoint;
											} // doesn't matter if it was the last point (first point in the list) as there is no following point to update the time for
											
											currentIndexInPointsToRemove = Math.min(toRemove.length-1, currentIndexInPointsToRemove+1);
										} else {
											trackUpdated.add(badTrack.get(i).copyOf()); // need copy of to get original interval back
										}
									}
									if (debug) System.out.println();
									
									// step 14. test it
									List<Integer> badPointsUpdated = getIndicesWithBadSpeedAndAngle(trackUpdated, cache, debug);
									
									// steps 15-16. if it's all ok, keep
									if (badPointsUpdated.isEmpty()) {
										done = true;
									} else { // otherwise, clear updated track and try again, unless this is the last one, in which case we'll keep the results of removing all points (this is checked in the last iteration), and update the route and list of bad points for trying again
										if (numberToRemove == badPoints.size()) {
											badPoints = badPointsUpdated;
											badTrack = new ArrayList<PointInTrack>(trackUpdated);
										}
										
										trackUpdated.clear();
									}
								} // end of loop over possible points to remove
							} // end of loop over increasing number of points to remove
						} // end of bad point fixing loop
						
						if (done) {
							if (debug) System.out.println("FIXED");
							comment = "fixed";
							countCleaned++;
							trackGood = true;
						} else {
							if (debug) System.out.println("NO FIXES FOUND");
							countUncleanable++;
							comment = "no fix found";
						}
					} // end of bad point fixing
				} else { // end of check for elements in track
					countEmptyTracks++;
					trackGood = false;
					comment = "too few elements in track";
				}
				flightTracksComments.add(comment);
				
				if (trackGood) {
					cols[columnIndices.getColumnIndex(RawFlightTrackData.HEADER_TRACK, true)] = trackToCoordsString(trackUpdated);
					countTotalInOutput++;
					out.println(ArrayTools.toString(cols, RawFlightTrackData.SEPARATOR));
				}
			} // end of loop over file
			
			in.close();
			out.close();
			
			tracksToKML(fileNameOut + "_Cleaned.kml", flightTracksOriginal, flightTracksUpdated, flightTracksComments);
			
			System.out.println("Cleaning done.");
			System.out.println("Raw tracks read:" + countRaw);
			System.out.println("Empty tracks discarded:" + countEmptyTracks);
			System.out.println("Tracks amended to remove points distant from airport:" + countPointsRemoteToAirportRemoved);
			System.out.println("Tracks discarded as uncleanable:" + countUncleanable);
			System.out.println("Tracks not needing cleaned:" + countGoodTracks);
			System.out.println("Tracks successfully cleaned:" + countCleaned);
			System.out.println("Tracks written to output:" + countTotalInOutput);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	private static String trackToCoordsString(List<PointInTrack> track) {
		StringBuffer buf = new StringBuffer();
		
		boolean first = true;
		for (PointInTrack p : track) {
			if (first) {
				first = false;
			} else {
				buf.append(RawFlightTrackData.SEPARATOR_COORDS);
			}
			
			buf.append(p.getLatLng().getLat());
			buf.append(RawFlightTrackData.SEPARATOR_COORDS);
			buf.append(p.getLatLng().getLng());
			buf.append(RawFlightTrackData.SEPARATOR_COORDS);
			buf.append(p.getAltitude()); // altitude
			buf.append(RawFlightTrackData.SEPARATOR_COORDS);
			buf.append(-1 * p.getTimeSinceLastPoint()); // convert back to negatives for consistency with original data 
		}
		
		return buf.toString();
	}
	
	/**assumes that points are in FR24 order - latest first, going back in time, with timings on each point being time from previous point (so the next point in the list)*/
	private static List<Integer> getIndicesWithBadSpeedAndAngle(List<PointInTrack> points, ExtremePointDecisionCache cache, boolean debug) {
		List<Integer> rval = new ArrayList<Integer>();
		
		if (points.size() < 3) { // not much to say if there are this few points
			return rval;
		}
		
		// check first element
		if ((points.get(0).altitude == 0) && endPointTooExtreme(points.get(0).getLatLng(), points.get(1).getLatLng(), points.get(2).getLatLng(), debug)) {
			rval.add(0);
		}
		
		for (int i = 1; i < points.size() - 1; i++) {
			PointInTrack prev = points.get(i - 1);
			PointInTrack cur = points.get(i);
			PointInTrack next = points.get(i + 1);
			if ((prev.getAltitude() == 0) && (cur.getAltitude() == 0) && (next.getAltitude() == 0)) { // only perform checks on points with zero altitude (landing/taking off ACs will be going much faster)
				Boolean decision = debug ? null : cache.getDecision(prev, cur, next);
			
				if (decision == null) {
					if (debug) System.out.print(i+":");
					decision = angleAndSpeedAroundPointTooExtreme(prev.getLatLng(), cur.getLatLng(), next.getLatLng(), prev.getTimeSinceLastPoint(), cur.getTimeSinceLastPoint(), debug);
					cache.addDecision(prev, cur, next, decision.booleanValue());
				}
				
				if (decision.booleanValue()) {
					rval.add(i);
				}
			}
		}
		
		// check last element
		if ((points.get(points.size() - 1).altitude == 0) && endPointTooExtreme(points.get(points.size() - 1).getLatLng(), points.get(points.size() - 2).getLatLng(), points.get(points.size() - 3).getLatLng(), debug)) {
			rval.add(points.size() - 1);
		}
		
		return rval;
	}
	
	/**
	 * perform tests on a point given its neighbours in the path
	 * currently uses fixed values for tests, noted in paper
	 */
	private static boolean angleAndSpeedAroundPointTooExtreme(LatLng prev, LatLng point, LatLng next, double timePrevToPoint, double timePointToNext, boolean debug) {
		double angle = Geography.angleBetweenPoints(prev, point, next);
		double distancePrevPoint = Geography.distance(prev, point);
		double distancePointNext = Geography.distance(point, next);
		double distancePrevNext = Geography.distance(prev, next);
		double speed = (distancePrevPoint + distancePointNext) / (timePrevToPoint + timePointToNext);
		
		// if two of the points are overlaid, then the angle calc is meaningless - reset to zero (no turning)
		if ((distancePrevPoint == 0) || (distancePointNext == 0)) {
			angle = 0;
		}
		
		// angle / speed tests
		// sometime it would be nice to parameterise these
		boolean testA1Fails = (angle > 90) && (speed > 30);
		boolean testA2Fails = (angle > 60) && (speed > 50); // needs to be higher speed to still keep runway turnoffs 
		boolean testA3Fails = (angle > 150) && (speed > 10); // anything this sharp needs to be slow
		boolean testA4Fails = (angle > 120) && (speed > 20); // anything this sharp needs to be slow
		boolean testA5Fails = (angle > 130) && (speed > 16.7);
		boolean testA6Fails = (angle > 140) && (speed > 13.3);
		
		// distance tests
		boolean testD1Fails = (distancePrevPoint > 100) && (distancePrevPoint > (5 * distancePrevNext)) && (distancePointNext > (5 * distancePrevNext)); // catch massive deviations. if two points are near each other, and the point between them is very far away, it can be dropped (only do this for larger jumps, ie >10m/100m)
		boolean testD2Fails = ((distancePrevPoint > 250) && (distancePointNext < 10) || (distancePrevPoint < 10) && (distancePointNext > 250)); // catch massive deviations. if this point is right next to other point (within 10m), but next one is 100m, then this is probably an outlier too
		
		if (debug) System.out.println("AS " + prev + "," + point + "," + next + "," + angle + "," + speed + "," + testA1Fails + "," + testA2Fails + "," + testA3Fails+ "," + testA4Fails + "," + testA5Fails + "," + testA6Fails + "," + testD1Fails + "," + testD2Fails);
		
		return testA1Fails || testA2Fails || testA3Fails || testA4Fails || testA5Fails || testA6Fails || testD1Fails || testD2Fails;
	}
	
	/**only checks distance wrt to distance between the next two points in*/
	private static boolean endPointTooExtreme(LatLng point, LatLng point1, LatLng point2, boolean debug) {
		double distancePointTo1 = Geography.distance(point, point1);
		double distance1To2 = Geography.distance(point1, point2);
		boolean testD1Fails = (distancePointTo1 > 100) && (distancePointTo1 > (20 * distance1To2)); // catch massive deviations
		
		if (debug) System.out.println("EP " + point + "," + point1 + "," + point2 + "," + distancePointTo1 + "," + distance1To2 + "," + testD1Fails);
		
		return testD1Fails;
	}
	
	private static void tracksToKML(String filename, List<List<PointInTrack>> originalRoutes, List<List<PointInTrack>> updatedRoutes, List<String> comments) {
		final Kml kml = KmlFactory.createKml();
		final Document document = kml.createAndSetDocument().withName(filename).withOpen(true);
		Document[] documents = new Document[originalRoutes.size()];
		for (int i = 0; i < documents.length; i++) {
			documents[i] = document.createAndAddDocument().withName(i + "_" + comments.get(i)).withOpen(false);
		}
		
		final Style styleOriginal = document.createAndAddStyle().withId("linestyleOriginal");
		styleOriginal.createAndSetLineStyle().withColor("ff0000ff").withWidth(4.0d);
		final Style styleUpdated = document.createAndAddStyle().withId("linestyleUpdated");
		styleUpdated.createAndSetLineStyle().withColor("ffff0000").withWidth(4.0d);

		for (int i = 0; i < documents.length; i++) {
			LineString lsO = documents[i].createAndAddPlacemark().withName("O").withStyleUrl("#linestyleOriginal").withVisibility(false).createAndSetLineString();
			//lsO.setAltitudeMode(AltitudeMode.RELATIVE_TO_GROUND);
			for (PointInTrack pip : originalRoutes.get(i)) {
				if (pip.getAltitude() == 0) {
					LatLng ll = pip.getLatLng();
					lsO.addToCoordinates(ll.getLng() + "," + ll.getLat() + ",0.0");// + pip.getAltitude()); // dropped altitude stuff as the track was sometimes hidden by small bumps in terrain
				}
			}
			
			LineString lsU = documents[i].createAndAddPlacemark().withName("U").withStyleUrl("#linestyleUpdated").withVisibility(false).createAndSetLineString();
			
			for (PointInTrack pip : updatedRoutes.get(i)) {
				if (pip.getAltitude() == 0) {
					LatLng ll = pip.getLatLng();
					lsU.addToCoordinates(ll.getLng() + "," + ll.getLat() + ",0,0");// + pip.getAltitude());
				}
			}
		}
		
		KMLUtils.addGroundOverlayToKMLDocument(filename, document);

		try {
			if (kml.marshal(new File(filename))) {
				System.out.println(filename + " written successfully");
			} else {
				System.out.println(filename + " not written");
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	// this could be made far more efficient if we just used indices into the original track rather than object lists
	// that way the cache could be 2 3D arrays of booleans; one for whether the test result is in the cache, one for test results
	private static class ExtremePointDecisionCache {
		// outer map is prev point, middle map is current point, inner map is next point
		private Map<PointInTrack, Map<PointInTrack, Map<PointInTrack, Boolean>>> cache;
		public ExtremePointDecisionCache() {
			this.cache = new HashMap<PointInTrack, Map<PointInTrack, Map<PointInTrack, Boolean>>>();
		}
		
		/**decision is null if cache miss, true/false otherwise*/
		public Boolean getDecision(PointInTrack prev, PointInTrack current, PointInTrack next) {
			Map<PointInTrack, Map<PointInTrack, Boolean>> m1 = this.cache.get(prev);
			if (m1 != null) {
				Map<PointInTrack, Boolean> m2 = m1.get(current);
				if (m2 != null) {
					Boolean b = m2.get(next);
					
					return b;
				}
			}
			
			return null;
		}
		
		public void addDecision(PointInTrack prev, PointInTrack current, PointInTrack next, boolean decision) {
			Map<PointInTrack, Map<PointInTrack, Boolean>> m1 = this.cache.get(prev);
			if (m1 == null) {
				m1 = new HashMap<PointInTrack, Map<PointInTrack, Boolean>>();
				this.cache.put(prev, m1);
			}
			
			Map<PointInTrack, Boolean> m2 = m1.get(current);
			if (m2 == null) {
				m2 = new HashMap<PointInTrack, Boolean>();
				m1.put(current, m2);
			}
				
			m2.put(next, Boolean.valueOf(decision));
		}
	}
	
	private static class PointInTrack {
		private LatLng latLng;
		private double timeSinceLastPoint;
		private double altitude;
		
		public PointInTrack(LatLng latLng, double timeSinceLastPoint, double altitude) {
			this.latLng = latLng;
			this.timeSinceLastPoint = timeSinceLastPoint;
			this.altitude = altitude;
		}
		
		public LatLng getLatLng() {
			return latLng;
		}
		
		public double getTimeSinceLastPoint() {
			return timeSinceLastPoint;
		}
		
		public double getAltitude() {
			return altitude;
		}
		
		public PointInTrack copyOf() {
			return new PointInTrack(latLng, timeSinceLastPoint, altitude);
		}
		
		@Override
		public String toString() {
			StringBuffer buf = new StringBuffer();
			buf.append("PIT[");
			buf.append(latLng.getLat());
			buf.append(",");
			buf.append(latLng.getLng());
			buf.append(",");
			buf.append(timeSinceLastPoint);
			buf.append(",");
			buf.append(altitude);
			buf.append("]");
			return buf.toString();
		}
	}
}
